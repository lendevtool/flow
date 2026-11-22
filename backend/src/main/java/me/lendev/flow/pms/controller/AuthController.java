package me.lendev.flow.pms.controller;

import cn.dev33.satoken.stp.StpUtil;
import me.lendev.flow.common.auth.Roles;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.auth.SessionRules;
import me.lendev.flow.policy.AuthPolicyAccess;
import me.lendev.flow.pms.domain.dto.TemporaryPasswordDto;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.common.auth.rate.AuthRateLimiter;
import me.lendev.flow.common.validation.PasswordPolicy;
import org.springframework.validation.annotation.Validated;
import me.lendev.flow.common.response.R;
import me.lendev.flow.pms.domain.dto.LoginTokenDto;
import me.lendev.flow.pms.domain.request.ChangePasswordRequest;
import me.lendev.flow.pms.domain.request.LoginRequest;
import me.lendev.flow.pms.domain.request.RegisterUserRequest;
import me.lendev.flow.pms.service.CaptchaService;
import me.lendev.flow.pms.service.UserService;
import cn.hutool.captcha.ICaptcha;
import cn.hutool.core.lang.Pair;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Cookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.util.WebUtils;
import cn.hutool.core.text.CharSequenceUtil;

import java.io.IOException;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 鉴权相关的Controller.
 *
 * @author lendev
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "鉴权")
public class AuthController {

    /**
     * 用户服务。
     */
    private final UserService userService;

    private final CaptchaService captchaService;

    private final AuthRateLimiter rateLimiter;
    private final AuthPolicyAccess policies;
    private final SessionRules sessions;

    private static final String CAPTCHA_KEY = "captchaKey";
    private static final String CAPTCHA_COOKIE = "flow-captcha";

    /**
     * 用户登录接口.
     *
     * @param request            登录请求（账号、密码、验证码等）
     * @param httpServletRequest HTTP 请求，用于登录限流取客户端 IP，以及在未显式传验证码 key 时从 Cookie/Session 兜底读取
     * @return 返回token
     */
    @PostMapping("/login")
    @Operation(summary = "登录")
    public R<LoginTokenDto> login(@RequestBody @Validated final LoginRequest request,
                                  HttpServletRequest httpServletRequest) {
        request.setRemoteAddress(httpServletRequest.getRemoteAddr());
        HttpSession session = httpServletRequest.getSession(false);
        if (CharSequenceUtil.isBlank(request.getCaptchaKey())) {
            Cookie cookie = WebUtils.getCookie(httpServletRequest, CAPTCHA_COOKIE);
            String captchaKey = null;
            if (cookie != null) {
                captchaKey = cookie.getValue();
            } else if (session != null) {
                captchaKey = (String) session.getAttribute(CAPTCHA_KEY);
            }
            request.setCaptchaKey(captchaKey);
        }
        // 验证码 key 为一次性凭据：取出后立即从 Session 移除，防止同一验证码被重放使用。
        if (session != null) {
            session.removeAttribute(CAPTCHA_KEY);
        }
        LoginTokenDto tokenDto = userService.login(request);
        return R.ok(tokenDto);
    }

    public record LoginOptions(boolean captchaRequired, boolean localEnabled, boolean domainEnabled) {

    }

    @GetMapping("/login/options")
    public R<LoginOptions> loginOptions() {
        var policy = policies.current();
        return R.ok(new LoginOptions(policy.captcha().required(), policy.login().localEnabled(),
                policy.login().domainEnabled()));
    }

    public record PasswordRules(int minLength, int maxLength, List<String> requiredCharacterTypes,
                                boolean rejectUsername, boolean rejectCurrent) {

    }

    /**
     * 返回当前设密校验规则，普通登录和待改密的受限会话均可读取。
     */
    @GetMapping("/password/rules")
    @Operation(summary = "获取当前密码规则")
    public R<PasswordRules> passwordRules() {
        var password = policies.current().password();
        return R.ok(new PasswordRules(password.minLength(), PasswordPolicy.MAX_LENGTH,
                password.requiredCharacterTypes(), password.rejectUsername(), password.rejectCurrent()));
    }

    /**
     * 注册（仅超级管理员可调用）
     *
     * @param request 用户注册请求
     * @return R
     */
    @Roles(RoleType.SUPER_ADMIN)
    @PostMapping("/register")
    @Operation(summary = "注册")
    public R<TemporaryPasswordDto> register(@RequestBody @Validated RegisterUserRequest request) {
        return R.ok(userService.register(request));
    }

    /**
     * 刷新token
     *
     * @return 新的token
     */
    @GetMapping("/refresh/token")
    @Operation(summary = "刷新token")
    public R<LoginTokenDto> refreshToken() {
        LoginTokenDto dto = userService.refreshToken();
        return R.ok(dto);
    }


    /**
     * 切换角色
     *
     * @param roleCode 角色代码
     * @return R
     */
    @PostMapping("/current-role/switch/{roleCode}")
    @Operation(summary = "切换用户")
    public R<LoginTokenDto> switchRole(@PathVariable String roleCode) {
        long userId = StpUtil.getLoginIdAsLong();
        LoginTokenDto tokenDto = userService.switchRole(userId, roleCode);
        return R.ok(tokenDto);
    }

    /**
     * 登出并删除当前 Token 对应的 Redis 登录态
     *
     * @return R
     */
    @PostMapping("/logout")
    @Audited(AuditOperation.LOGOUT)
    @Operation(summary = "登出")
    public R<Object> logout() {
        sessions.logout();
        return R.ok();
    }

    /**
     * 启用时生成图形验证码，并通过响应头与 HttpOnly Cookie 下发验证码 key。
     *
     * @param request  HTTP 请求，用于验证码限流
     * @param response HTTP 响应，输出 PNG 图片或在关闭时返回 204
     * @throws IOException 写出图片流失败时抛出
     */
    @GetMapping("/captcha")
    @Operation(summary = "验证码")
    public void captcha(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var policy = policies.current().captcha();
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (!policy.required()) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        rateLimiter.checkCaptcha(request.getRemoteAddr());
        long ttl = policy.ttlSeconds();
        Pair<String, ICaptcha> captchaPair = captchaService.create(ttl);
        response.setContentType("image/png");
        response.setHeader("X-Captcha-Key", captchaPair.getKey());
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(CAPTCHA_COOKIE, captchaPair.getKey())
                .httpOnly(true).secure(request.isSecure()).sameSite("Lax").path("/").maxAge(ttl).build().toString());
        captchaPair.getValue().write(response.getOutputStream());
    }


    /**
     * 修改密码
     *
     * @param request 修改密码请求（旧密码、新密码）
     * @return R
     */
    @PostMapping("/password")
    @Operation(summary = "修改密码")
    public R<Object> changePassword(@RequestBody @Validated ChangePasswordRequest request) {
        userService.changePassword(request);
        return R.ok();
    }

}
