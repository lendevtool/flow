package me.lendev.flow.common.auth.rate;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import me.lendev.flow.support.TestRedis;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class AuthRateLimiterTest {

    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connectToIsolatedTestRedis() {
        connections = new LettuceConnectionFactory(TestRedis.configuration());
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        connections.destroy();
    }

    private static String unique() {
        return UUID.randomUUID().toString();
    }

    private static RateLimitProperties quotas(int ip, int account, int captcha) {
        var properties = new RateLimitProperties();
        properties.setLoginPerIp(ip);
        properties.setLoginPerAccount(account);
        properties.setCaptchaPerIp(captcha);
        return properties;
    }

    @Test
    void concurrentRequestsAcrossInstancesCannotExceedTheQuota() throws Exception {
        var properties = quotas(5, 5, 5);
        var first = new AuthRateLimiter(redis, properties);
        var second = new AuthRateLimiter(redis, properties);
        String ip = unique();
        String username = unique();
        // 两个 limiter 实例共享同一份 Redis 计数；40 个并发请求经闩锁同时放行，
        // 验证跨实例的分布式限流仍然只放行配额内的 5 个请求。
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Boolean>>();
        try (var workers = Executors.newFixedThreadPool(12)) {
            for (int attempt = 0; attempt < 40; attempt++) {
                var limiter = attempt % 2 == 0 ? first : second;
                results.add(workers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        limiter.checkLogin(ip, username);
                        return true;
                    } catch (TooManyRequestsException exception) {
                        assertTrue(exception.getRetryAfterSeconds() >= 1);
                        return false;
                    }
                }));
            }
            start.countDown();
            int permitted = 0;
            for (var result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    permitted++;
                }
            }
            assertEquals(5, permitted);
        }
    }

    @Test
    void loginIpQuotaIsIndependentOfTheAccount() {
        var limiter = new AuthRateLimiter(redis, quotas(2, 10, 10));
        String ip = unique();
        limiter.checkLogin(ip, unique());
        limiter.checkLogin(ip, unique());
        String blockedUsername = unique();
        assertThrows(TooManyRequestsException.class, () -> limiter.checkLogin(ip, blockedUsername));
        assertDoesNotThrow(() -> limiter.checkLogin(unique(), unique()));
    }

    @Test
    void blockedIpDoesNotCreateCountersForArbitraryFreshUsernames() throws Exception {
        var limiter = new AuthRateLimiter(redis, quotas(1, 10, 10));
        String ip = unique();
        limiter.checkLogin(ip, unique());
        String username = unique();
        assertThrows(TooManyRequestsException.class, () -> limiter.checkLogin(ip, username));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(username.getBytes(StandardCharsets.UTF_8)));
        assertFalse(redis.hasKey("flow:auth-rate:{auth-rate}:login:account:" + digest));
    }

    @Test
    void accountQuotaIsSharedAcrossIpAddressesAndUsernameCase() {
        var limiter = new AuthRateLimiter(redis, quotas(10, 2, 10));
        String username = "Account-" + unique();
        limiter.checkLogin(unique(), username);
        limiter.checkLogin(unique(), username.toUpperCase(java.util.Locale.ROOT));
        String blockedIp = unique();
        assertThrows(TooManyRequestsException.class,
                () -> limiter.checkLogin(blockedIp, username));
        assertDoesNotThrow(() -> limiter.checkLogin(unique(), unique()));
    }

    @Test
    void accentVariantsConservativelyShareTheAccountQuota() {
        var limiter = new AuthRateLimiter(redis, quotas(10, 2, 10));
        String suffix = unique();
        limiter.checkLogin(unique(), "rate-" + suffix);
        limiter.checkLogin(unique(), "RÁTÉ-" + suffix);
        String blockedIp = unique();
        assertThrows(TooManyRequestsException.class,
                () -> limiter.checkLogin(blockedIp, "ra\u0302te-" + suffix));
    }

    @Test
    void captchaHasAnIndependentOperationQuota() {
        var limiter = new AuthRateLimiter(redis, quotas(1, 1, 1));
        String ip = unique();
        limiter.checkLogin(ip, unique());
        limiter.checkCaptcha(ip);
        assertThrows(TooManyRequestsException.class, () -> limiter.checkCaptcha(ip));
    }

    @Test
    void windowExpiresWithoutRejectedRequestsExtendingIt() throws Exception {
        var properties = quotas(1, 1, 1);
        properties.setWindowSeconds(1);
        var limiter = new AuthRateLimiter(redis, properties);
        String ip = unique();
        limiter.checkCaptcha(ip);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(ip.getBytes(StandardCharsets.UTF_8)));
        String key = "flow:auth-rate:{auth-rate}:captcha:ip:" + digest;
        await().atMost(Duration.ofSeconds(3)).until(() -> {
            Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
            return ttl != null && ttl > 0 && ttl <= 700;
        });
        Long remainingBeforeRejection = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertEquals(1, assertThrows(TooManyRequestsException.class,
                () -> limiter.checkCaptcha(ip)).getRetryAfterSeconds());
        Long remainingAfterRejection = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertNotNull(remainingBeforeRejection);
        assertNotNull(remainingAfterRejection);
        assertTrue(remainingAfterRejection <= remainingBeforeRejection,
                "Rejected requests must not extend the fixed window");
        await().atMost(Duration.ofSeconds(3)).until(() -> !redis.hasKey(key));
        assertDoesNotThrow(() -> limiter.checkCaptcha(ip));
    }

    @Test
    void hashedKeysHaveFixedLengthAndAnExpiry() throws Exception {
        var limiter = new AuthRateLimiter(redis, quotas(2, 2, 2));
        String ip = "ip-" + unique();
        String username = "account-" + unique() + "x".repeat(10000);
        limiter.checkLogin(ip, username);
        for (var entry : java.util.Map.of("ip", ip, "account", username).entrySet()) {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(entry.getValue().getBytes(StandardCharsets.UTF_8)));
            String key = "flow:auth-rate:{auth-rate}:login:" + entry.getKey() + ":" + digest;
            assertEquals("1", redis.opsForValue().get(key));
            assertTrue(redis.getExpire(key) > 0);
            assertTrue(key.length() < 120);
            assertFalse(key.contains(entry.getValue()));
        }
    }

    @Test
    void disabledLimiterDoesNotRequireRedis() {
        var properties = quotas(1, 1, 1);
        properties.setEnabled(false);
        var limiter = new AuthRateLimiter(null, properties);
        assertDoesNotThrow(() -> limiter.checkLogin(null, null));
        assertDoesNotThrow(() -> limiter.checkCaptcha(null));
    }

    @Test
    void defaultsAreSafeAndConfigurationBoundsAreEnforced() {
        var properties = new RateLimitProperties();
        assertTrue(properties.isEnabled());
        assertEquals(30, properties.getLoginPerIp());
        assertEquals(10, properties.getLoginPerAccount());
        assertEquals(30, properties.getCaptchaPerIp());
        assertEquals(60, properties.getWindowSeconds());
        assertThrows(IllegalArgumentException.class, () -> properties.setLoginPerIp(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setLoginPerAccount(100001));
        assertThrows(IllegalArgumentException.class, () -> properties.setCaptchaPerIp(-1));
        assertThrows(IllegalArgumentException.class, () -> properties.setWindowSeconds(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setWindowSeconds(3601));
    }
}
