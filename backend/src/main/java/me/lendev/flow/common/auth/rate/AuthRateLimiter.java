package me.lendev.flow.common.auth.rate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.function.Supplier;

import me.lendev.flow.policy.AuthPolicy;
import me.lendev.flow.policy.AuthPolicyAccess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 认证接口限流器。
 *
 * <p>采用共享的固定窗口计数，统计的是被放行的尝试次数（包括登录失败的请求）。</p>
 */
@Service
public class AuthRateLimiter {

    // 使用共享的 hash tag（{auth-rate}），保证两个登录维度的键落在 Redis Cluster 同一槽位，脚本才能原子执行
    private static final String PREFIX = "flow:auth-rate:{auth-rate}:";
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    // Lua 脚本实现固定窗口限流：先检查所有窗口是否超限（超限则返回建议的重试秒数），
    // 全部通过后才对所有窗口计数，避免部分维度被计数造成配额浪费
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local retry = 0
            local window = tonumber(ARGV[1])
            for i, key in ipairs(KEYS) do
                local limit = tonumber(ARGV[i + 1])
                local count = tonumber(redis.call('GET', key) or '0')
                if count >= limit then
                    local ttl = redis.call('PTTL', key)
                    if ttl < 0 then
                        redis.call('PEXPIRE', key, window)
                        ttl = window
                    end
                    retry = math.max(retry, math.max(1, math.ceil(ttl / 1000)))
                end
            end
            if retry > 0 then
                return retry
            end
            for _, key in ipairs(KEYS) do
                local count = redis.call('INCR', key)
                if count == 1 or redis.call('PTTL', key) < 0 then
                    redis.call('PEXPIRE', key, window)
                end
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Supplier<AuthPolicy.RateLimit> policy;

    @Autowired
    public AuthRateLimiter(StringRedisTemplate redis, AuthPolicyAccess access) {
        this(redis, () -> access.current().rateLimit());
    }

    AuthRateLimiter(StringRedisTemplate redis, RateLimitProperties initial) {
        this(redis, initial::toPolicy);
    }

    private AuthRateLimiter(StringRedisTemplate redis, Supplier<AuthPolicy.RateLimit> policy) {
        this.redis = redis;
        this.policy = policy;
    }

    /**
     * 登录限流检查，应在验证码校验、数据库访问和 BCrypt 密码运算之前调用。
     *
     * @param remoteAddr 客户端 IP
     * @param username   登录用户名
     */
    public void checkLogin(String remoteAddr, String username) {
        var properties = policy.get();
        if (!properties.enabled()) {
            return;
        }
        // 保守的规范化折叠（小写 + NFKD 去组合符），让大小写、重音、Unicode 变体共享同一账号配额，防止绕过限流
        String account = username == null ? "" : COMBINING_MARKS.matcher(Normalizer.normalize(
                username.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)).replaceAll("");
        acquire(properties.windowSeconds(), List.of(key("login:ip", remoteAddr), key("login:account", account)),
                Integer.toString(properties.loginPerIp()),
                Integer.toString(properties.loginPerAccount()));
    }

    /**
     * 验证码限流检查，应在生成图片或存储 challenge 之前调用。
     *
     * @param remoteAddr 客户端 IP
     */
    public void checkCaptcha(String remoteAddr) {
        var properties = policy.get();
        if (!properties.enabled()) {
            return;
        }
        acquire(properties.windowSeconds(), List.of(key("captcha:ip", remoteAddr)),
                Integer.toString(properties.captchaPerIp()));
    }

    /**
     * 按账号限制旧密码验证。
     */
    public void checkPasswordChange(long userId) {
        var properties = policy.get();
        if (properties.enabled()) {
            acquire(properties.windowSeconds(), List.of(key("change-password:account", Long.toString(userId))),
                    Integer.toString(properties.changePasswordPerAccount()));
        }
    }

    private void acquire(int windowSeconds, List<String> keys, String... limits) {
        Object[] arguments = new Object[limits.length + 1];
        arguments[0] = Long.toString(windowSeconds * 1000L);
        System.arraycopy(limits, 0, arguments, 1, limits.length);
        Long retryAfter = redis.execute(ACQUIRE, keys, arguments);
        if (retryAfter == null) {
            throw new IllegalStateException("Redis returned no rate-limit decision");
        }
        if (retryAfter > 0) {
            throw new TooManyRequestsException(retryAfter);
        }
    }

    // 维度值（IP、用户名）经 SHA-256 摘要后再拼键，避免敏感明文写入 Redis 键名，同时定长化
    private static String key(String dimension, String value) {
        try {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            return PREFIX + dimension + ":" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
