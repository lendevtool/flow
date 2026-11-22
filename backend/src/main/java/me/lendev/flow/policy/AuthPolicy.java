package me.lendev.flow.policy;

import java.util.List;

/**
 * 认证请求使用的一份已验证的策略快照，时长以秒计。
 */
public record AuthPolicy(Password password, Bootstrap bootstrap, Session session, Captcha captcha,
                         RateLimit rateLimit, Login login, Mfa mfa) {

    public static final String CODE = "AUTH_SECURITY";

    public record Password(int minLength, List<String> requiredCharacterTypes, boolean rejectUsername,
                           boolean rejectCurrent, long maxAgeSeconds, long temporaryTtlSeconds) {

    }

    public record Mfa(boolean required, int keyRotationDays, int keyReminderDays) {

    }

    public record Login(boolean localEnabled, boolean domainEnabled) {

    }

    public record Bootstrap(boolean requirePasswordChange) {

    }

    public record Session(long idleTimeoutSeconds, long absoluteTimeoutSeconds, boolean allowConcurrent) {

    }

    public record Captcha(boolean required, long ttlSeconds) {

    }

    public record RateLimit(boolean enabled, int windowSeconds, int loginPerIp, int loginPerAccount,
                            int captchaPerIp, int changePasswordPerAccount) {

    }
}
