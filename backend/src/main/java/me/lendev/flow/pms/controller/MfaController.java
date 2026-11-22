package me.lendev.flow.pms.controller;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.mfa.MfaService;
import me.lendev.flow.common.response.R;
import me.lendev.flow.pms.domain.dto.LoginTokenDto;
import me.lendev.flow.pms.service.UserService;
import org.springframework.web.bind.annotation.*;

/**
 * 登录二次验证和个人 MFA 设置的 HTTP 入口。
 */
@RestController
@RequestMapping("/auth/mfa")
@RequiredArgsConstructor
public class MfaController {

    private final MfaService mfa;
    private final UserService users;

    public record VerifyRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String ticket,
                                @NotBlank @Size(max = 64) String code) {

        @Override
        public String toString() {
            return "VerifyRequest";
        }
    }

    public record SetupRequest(@Size(max = 64) String currentCode) {

        @Override
        public String toString() {
            return "SetupRequest";
        }
    }

    public record PreferenceRequest(@NotNull Boolean enabled, @NotBlank @Size(max = 64) String code) {

        @Override
        public String toString() {
            return "PreferenceRequest[enabled=" + enabled + "]";
        }
    }

    /**
     * 消费登录挑战并完成二次验证，通过后签发会话。
     *
     * @param request 登录阶段签发的票据及动态验证码或恢复码
     * @return 登录令牌，首次绑定时可包含新生成的恢复码
     */
    @PostMapping("/verify")
    public R<LoginTokenDto> verify(@Valid @RequestBody VerifyRequest request) {
        return R.ok(users.completeMfaLogin(request.ticket(), request.code()));
    }

    /**
     * 读取当前会话用户的 MFA 状态。
     *
     * @return 当前绑定和个人启用状态，以及系统策略要求
     */
    @GetMapping
    public R<MfaService.Status> status() {
        return R.ok(mfa.status(StpUtil.getLoginIdAsLong()));
    }

    /**
     * 为当前用户创建限时设备绑定挑战。
     *
     * @param request 设置请求，更换设备时提供原设备验证码或恢复码，符合近期恢复码登录条件时可省略
     * @return 用于配置验证器的绑定挑战
     */
    @PostMapping("/setup")
    public R<MfaService.Challenge> setup(@Valid @RequestBody SetupRequest request) {
        return R.ok(mfa.startSetup(StpUtil.getLoginIdAsLong(), request.currentCode()));
    }

    /**
     * 验证绑定挑战并保存新设备，成功时返回恢复码。
     *
     * @param request 设置阶段签发的票据及新设备生成的验证码
     * @return 绑定结果和本次生成的恢复码
     */
    @PostMapping("/setup/confirm")
    public R<MfaService.Enrollment> confirm(@Valid @RequestBody VerifyRequest request) {
        return R.ok(mfa.confirmSetup(StpUtil.getLoginIdAsLong(), request.ticket(), request.code()));
    }

    /**
     * 校验凭据后更新当前用户的个人 MFA 偏好。
     *
     * @param request 目标启用状态和当前设备验证码或恢复码
     * @return 更新后的个人与策略状态
     */
    @PutMapping("/preference")
    public R<MfaService.Status> preference(@Valid @RequestBody PreferenceRequest request) {
        return R.ok(mfa.preference(StpUtil.getLoginIdAsLong(), request.enabled(), request.code()));
    }
}
