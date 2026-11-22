package me.lendev.flow.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.exception.BizException;
import me.lendev.flow.common.response.BizResponseCode;
import me.lendev.flow.pms.domain.entity.User;
import me.lendev.flow.pms.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SaToken的配置类
 */
@Configuration
@RequiredArgsConstructor
public class SaTokenConfigure implements WebMvcConfigurer {

    public static final String CURRENT_ROLE_KEY = "currentRoleCode";
    private static final String[] API_DOCS_PATHS = {"/doc.html", "/swagger-ui.html", "/swagger-ui/**",
            "/webjars/**", "/v3/api-docs/**", "/v3/api-docs.yaml/**"};

    private final UserMapper userMapper;

    private final SensitiveResponseInterceptor sensitiveResponseInterceptor;
    private final SessionRules sessions;

    @Value("${flow.api-docs.public:false}")
    private boolean publicApiDocs;

    /**
     * 获取当前令牌所选角色；角色与令牌（而非账号）绑定， 因此同一账号的多次登录可以各自选择不同角色。
     *
     * @return 当前角色编码，未登录或无会话时返回空字符串
     */
    public static String currentRole() {
        var session = StpUtil.getStpLogic().getTokenSession(false);
        return session == null ? "" : java.util.Objects.toString(session.get(CURRENT_ROLE_KEY), "");
    }

    /**
     * 获取当前令牌所选角色，账号无任何可用角色时直接拒绝
     *
     * @return 当前角色编码
     */
    public static String requireCurrentRole() {
        if (StpUtil.getRoleList().isEmpty()) {
            throw new BizException(BizResponseCode.ERR_11005);
        }
        return currentRole();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sensitiveResponseInterceptor)
                .addPathPatterns("/auth/**", "/user/**", "/role/**", "/permission/**", "/param/**", "/audit/**",
                        "/system-policy/**", "/workbench/**", "/workflow/**", "/mail/**", "/in-app-messages/**",
                        "/department/**", "/notification-groups/**", "/admin/open-api/**", "/documents/**",
                        "/document-generation/**", "/institutions/**", "/field-definition/**", "/trading-calendar/**")
                .addPathPatterns(API_DOCS_PATHS);
        var authentication = registry.addInterceptor(new HandlerInterceptor() {
                    private static final String ACTIVE_TOKEN = SaTokenConfigure.class.getName() + ".activeToken";

                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        StpUtil.checkLogin();
                        User user = userMapper.selectOneById(StpUtil.getLoginIdAsLong());
                        if (user == null || !Boolean.TRUE.equals(user.getEnable())) {
                            throw new BizException(BizResponseCode.ERR_10002);
                        }
                        AccountSession.check(user);
                        String path = request.getServletPath();
                        if (path.isEmpty()) {
                            path = request.getRequestURI().substring(request.getContextPath().length());
                        }
                        boolean allowedDuringPasswordChange = switch (request.getMethod()) {
                            case "POST" -> "/auth/password".equals(path) || "/auth/logout".equals(path);
                            case "GET" -> "/auth/password/rules".equals(path);
                            default -> false;
                        };
                        String token = sessions.check(user, allowedDuringPasswordChange);
                        if (!allowedDuringPasswordChange && !"/auth/refresh/token".equals(path)
                                && !"true".equalsIgnoreCase(request.getHeader("X-Session-Passive"))) {
                            request.setAttribute(ACTIVE_TOKEN, token);
                        }
                        return true;
                    }

                    @Override
                    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                                           org.springframework.web.servlet.ModelAndView modelAndView) {
                        if (request.getAttribute(ACTIVE_TOKEN) instanceof String token) {
                            sessions.recordActivity(token);
                        }
                    }
                })
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/login", "/auth/login/options", "/auth/mfa/verify")
                .excludePathPatterns("/auth/captcha")
                .excludePathPatterns("/favicon.ico")
                .excludePathPatterns("/oauth2/token", "/open/v1/**");
        if (publicApiDocs) {
            authentication.excludePathPatterns(API_DOCS_PATHS);
        }
    }

}
