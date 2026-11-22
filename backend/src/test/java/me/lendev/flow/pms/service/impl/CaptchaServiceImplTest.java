package me.lendev.flow.pms.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import me.lendev.flow.ServeApplicationTests;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class CaptchaServiceImplTest extends ServeApplicationTests {

    @Autowired
    private CaptchaServiceImpl service;
    @Autowired
    private StringRedisTemplate redis;

    @Test
    void differentInstancesShareChallenges() {
        var captcha = service.create();
        // 验证码存放在 Redis 中，多实例共享：另一实例校验（不区分大小写）成功后即消费，
        // 原实例再用正确验证码也应失败，保证一次性使用。
        var other = new CaptchaServiceImpl(new StringRedisTemplate(redis.getConnectionFactory()));
        assertTrue(other.verify(captcha.getKey(), captcha.getValue().getCode().toLowerCase()));
        assertFalse(service.verify(captcha.getKey(), captcha.getValue().getCode()));
    }

    @Test
    void challengesExpireInRedis() {
        var captcha = service.create();
        String key = "flow:captcha:" + captcha.getKey();
        Long ttl = redis.getExpire(key);
        assertTrue(ttl != null && ttl > 0 && ttl <= 60);
        redis.expire(key, Duration.ZERO);
        assertFalse(service.verify(captcha.getKey(), captcha.getValue().getCode()));
    }

    @Test
    void blankInputIsRejectedAndAnAttemptConsumesTheChallenge() {
        assertFalse(service.verify(null, "1234"));
        assertFalse(service.verify("", "1234"));
        var captcha = service.create();
        assertFalse(service.verify(captcha.getKey(), null));
        assertFalse(service.verify(captcha.getKey(), captcha.getValue().getCode()));
    }


    @Test
    void concurrentAttemptsCanConsumeChallengeOnlyOnce() throws Exception {
        // 16 个线程经闩锁同时校验同一验证码，依赖 Redis 原子消费保证仅 1 个成功。
        try (var executor = Executors.newFixedThreadPool(16)) {
            var captcha = service.create();
            String code = captcha.getValue().getCode();
            CountDownLatch start = new CountDownLatch(1);
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 16; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return service.verify(captcha.getKey(), code);
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertEquals(1, successes);
            var wrong = service.create();
            assertFalse(service.verify(wrong.getKey(), "!!!!"));
            assertFalse(service.verify(wrong.getKey(), wrong.getValue().getCode()));
        }
    }
}
