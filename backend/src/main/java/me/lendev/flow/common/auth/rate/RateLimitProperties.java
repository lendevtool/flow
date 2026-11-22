package me.lendev.flow.common.auth.rate;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 限流配置项，对应 {@code security.rate-limit} 前缀。
 *
 * <p>各限额统计的是被放行的请求次数（包括登录失败的请求）。</p>
 */
@Getter
@Component
@ConfigurationProperties(prefix = "security.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;
    private int loginPerIp = 30;
    private int loginPerAccount = 10;
    private int captchaPerIp = 30;
    private int windowSeconds = 60;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setLoginPerIp(int loginPerIp) {
        this.loginPerIp = bounded(loginPerIp, 100000, "login-per-ip");
    }

    public void setLoginPerAccount(int loginPerAccount) {
        this.loginPerAccount = bounded(loginPerAccount, 100000, "login-per-account");
    }

    public void setCaptchaPerIp(int captchaPerIp) {
        this.captchaPerIp = bounded(captchaPerIp, 100000, "captcha-per-ip");
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = bounded(windowSeconds, 3600, "window-seconds");
    }

    /**
     * 将旧部署配置迁入首次初始化的数据库策略。
     */
    public me.lendev.flow.policy.AuthPolicy.RateLimit toPolicy() {
        return new me.lendev.flow.policy.AuthPolicy.RateLimit(enabled, windowSeconds,
                loginPerIp, loginPerAccount, captchaPerIp, 5);
    }

    // 启动即校验配置边界，防止误配（如 0 或超大值）让限流失效或拒绝所有请求
    private static int bounded(int value, int maximum, String name) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(name + " must be between 1 and " + maximum);
        }
        return value;
    }
}
