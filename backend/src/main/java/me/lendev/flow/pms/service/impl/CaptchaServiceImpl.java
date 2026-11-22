package me.lendev.flow.pms.service.impl;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.CircleCaptcha;
import cn.hutool.captcha.ICaptcha;
import cn.hutool.core.lang.Pair;
import cn.hutool.core.lang.UUID;

import java.time.Duration;
import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.pms.service.CaptchaService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 验证码服务实现：验证码存放在 Redis 中共享并自动过期， 校验时通过 Lua 脚本原子地"读取并删除"，保证多实例部署下同一验证码只能被消费一次。
 */
@Service
@RequiredArgsConstructor
public class CaptchaServiceImpl implements CaptchaService {

    private static final String KEY_PREFIX = "flow:captcha:";
    private static final DefaultRedisScript<String> CONSUME = new DefaultRedisScript<>(
            "local code = redis.call('GET', KEYS[1]); redis.call('DEL', KEYS[1]); return code", String.class);

    private final StringRedisTemplate redis;

    @Override
    public Pair<String, ICaptcha> create(long ttlSeconds) {
        CircleCaptcha captcha = CaptchaUtil.createCircleCaptcha(80, 40, 4, 4);
        String key = UUID.randomUUID().toString(true);
        redis.opsForValue().set(KEY_PREFIX + key, captcha.getCode(), Duration.ofSeconds(ttlSeconds));
        return Pair.of(key, captcha);
    }

    @Override
    public boolean verify(String key, String code) {
        if (key == null || key.isBlank()) {
            return false;
        }
        // 用 Lua 脚本而非 GETDEL：需兼容 Redis 6.0，GETDEL 要求 Redis 6.2+。
        String expected = redis.execute(CONSUME, List.of(KEY_PREFIX + key));
        return expected != null && expected.equalsIgnoreCase(code);
    }
}
