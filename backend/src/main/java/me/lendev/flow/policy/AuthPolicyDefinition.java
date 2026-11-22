package me.lendev.flow.policy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.domain.DomainAuthenticator;
import me.lendev.flow.common.auth.mfa.MfaSecrets;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.validation.PasswordPolicy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 认证策略的默认值、允许的字段和整组校验。
 */
@Component
@RequiredArgsConstructor
public class AuthPolicyDefinition implements PolicyDefinition {

    public static final List<String> CHARACTER_TYPES = List.of("uppercase", "lowercase", "digit", "special");
    private static final AuthPolicy DEFAULTS = new AuthPolicy(
            new AuthPolicy.Password(12, CHARACTER_TYPES, true, true, 0, 86400),
            new AuthPolicy.Bootstrap(true), new AuthPolicy.Session(1800, 28800, true),
            new AuthPolicy.Captcha(true, 60), new AuthPolicy.RateLimit(true, 60, 30, 10, 30, 5),
            new AuthPolicy.Login(true, false), new AuthPolicy.Mfa(false, 365, 30));

    private final ObjectMapper json;
    private final AuthPolicyRepository repository;
    private final DomainAuthenticator domain;
    private final MfaSecrets mfaSecrets;

    @Override
    public String code() {
        return AuthPolicy.CODE;
    }

    @Override
    public String label() {
        return "认证安全策略";
    }

    @Override
    public int schemaVersion() {
        return 4;
    }

    @Override
    public JsonNode defaults() {
        return json.readTree(json.writeValueAsString(DEFAULTS));
    }

    @Override
    public void validate(JsonNode config) {
        verifyShape(config, defaults());
        var policy = decode(config);
        if (!policy.login().localEnabled() && !policy.login().domainEnabled()) {
            throw BadRequestException.localized("error.policy.login-method.required");
        }
        var password = policy.password();
        var types = password.requiredCharacterTypes();
        var session = policy.session();
        var rate = policy.rateLimit();
        var mfa = policy.mfa();
        if (!within(password.minLength(), 8, PasswordPolicy.MAX_LENGTH) || !CHARACTER_TYPES.containsAll(types)
                || new HashSet<>(types).size() != types.size()
                || !within(password.maxAgeSeconds(), 0, Integer.MAX_VALUE)
                || !positiveDuration(password.temporaryTtlSeconds())
                || !positiveDuration(session.idleTimeoutSeconds()) || !positiveDuration(session.absoluteTimeoutSeconds())
                || session.idleTimeoutSeconds() >= session.absoluteTimeoutSeconds()
                || !positiveDuration(policy.captcha().ttlSeconds())
                || !within(rate.windowSeconds(), 1, 3600)
                || !quota(rate.loginPerIp()) || !quota(rate.loginPerAccount()) || !quota(rate.captchaPerIp())
                || !quota(rate.changePasswordPerAccount())
                || !within(mfa.keyRotationDays(), 1, 3650)
                || !within(mfa.keyReminderDays(), 0, mfa.keyRotationDays() - 1L)) {
            throw invalid();
        }
    }

    @Override
    public JsonNode normalize(JsonNode config) {
        validate(config);
        var normalized = config.deepCopy();
        var types = decode(config).password().requiredCharacterTypes();
        var target = (tools.jackson.databind.node.ArrayNode) normalized.path("password").path("requiredCharacterTypes");
        target.removeAll();
        CHARACTER_TYPES.stream().filter(types::contains).forEach(target::add);
        return normalized;
    }

    /**
     * 在保存事务内校验认证方式切换条件，并在启用密码有效期时补齐正式密码的起始时间。
     */
    @Override
    public void beforeChange(JsonNode before, JsonNode after, Instant now) {
        var previous = decode(before);
        var next = decode(after);
        if (next.mfa().required() && !previous.mfa().required() && !mfaSecrets.configured()) {
            throw BadRequestException.localized("error.mfa.not-configured");
        }
        if (next.login().domainEnabled()
                && (!previous.login().domainEnabled() || previous.login().localEnabled() && !next.login().localEnabled())
                && !domain.configured()) {
            throw BadRequestException.localized("error.policy.domain.not-configured");
        }
        if (previous.login().localEnabled() && !next.login().localEnabled() && repository.countEnabledDomainAdmins() == 0) {
            throw BadRequestException.localized("error.policy.domain.admin-required");
        }
        if (previous.password().maxAgeSeconds() == 0 && next.password().maxAgeSeconds() > 0) {
            repository.initializePasswordChangedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        }
    }

    AuthPolicy decode(JsonNode config) {
        return json.treeToValue(config, AuthPolicy.class);
    }

    private static void verifyShape(JsonNode value, JsonNode template) {
        if (value == null || value.isNull() || value.getNodeType() != template.getNodeType()) {
            throw invalid();
        }
        if (template.isObject()) {
            if (value.size() != template.size()) {
                throw invalid();
            }
            template.properties().forEach(entry -> verifyShape(value.get(entry.getKey()), entry.getValue()));
        } else if (template.isArray()) {
            if (value.size() > CHARACTER_TYPES.size()) {
                throw invalid();
            }
            value.forEach(item -> verifyShape(item, template.get(0)));
        } else if (template.isIntegralNumber() && (!value.isIntegralNumber() || !value.canConvertToLong()
                || template.isInt() && !value.canConvertToInt())) {
            throw invalid();
        }
    }

    private static BadRequestException invalid() {
        return BadRequestException.localized("error.policy.invalid");
    }

    private static boolean positiveDuration(long value) {
        return within(value, 1, Integer.MAX_VALUE);
    }

    private static boolean quota(int value) {
        return within(value, 1, 100000);
    }

    private static boolean within(long value, long min, long max) {
        return value >= min && value <= max;
    }

    @Override
    public Map<String, String> fieldLabels() {
        return Map.ofEntries(
                Map.entry("mfa.required", "全局强制 MFA"),
                Map.entry("mfa.keyRotationDays", "MFA 密钥轮转周期"),
                Map.entry("mfa.keyReminderDays", "MFA 密钥到期提前提醒"),
                Map.entry("login.localEnabled", "允许本地登录"),
                Map.entry("login.domainEnabled", "允许域登录"),
                Map.entry("password.minLength", "密码最低长度"),
                Map.entry("password.requiredCharacterTypes", "密码复杂度"),
                Map.entry("password.rejectUsername", "禁止密码与账号名相同"),
                Map.entry("password.rejectCurrent", "禁止重复使用当前密码"),
                Map.entry("password.maxAgeSeconds", "正式密码最长使用时长"),
                Map.entry("password.temporaryTtlSeconds", "临时密码有效期"),
                Map.entry("bootstrap.requirePasswordChange", "初始化管理员首次登录须换密"),
                Map.entry("session.idleTimeoutSeconds", "会话空闲时长"),
                Map.entry("session.absoluteTimeoutSeconds", "会话最长时长"),
                Map.entry("session.allowConcurrent", "允许并行登录"),
                Map.entry("captcha.required", "全局图形验证码"),
                Map.entry("captcha.ttlSeconds", "验证码有效期"),
                Map.entry("rateLimit.enabled", "启用认证限流"),
                Map.entry("rateLimit.windowSeconds", "限流窗口"),
                Map.entry("rateLimit.loginPerIp", "每 IP 登录次数"),
                Map.entry("rateLimit.loginPerAccount", "每账号登录次数"),
                Map.entry("rateLimit.captchaPerIp", "每 IP 验证码申请次数"),
                Map.entry("rateLimit.changePasswordPerAccount", "每账号旧密码验证次数"));
    }
}
