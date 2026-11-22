package me.lendev.flow.openapi;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 用 Redis 时间统一计算固定窗口，并原子检查同应用的两级额度。
 */
@Component
@RequiredArgsConstructor
class OpenApiRateLimits {
    private static final int SIGNING_WINDOW = 60;
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local now = tonumber(redis.call('TIME')[1])
            local max_wait = 0
            local slots = {}
            local reset_times = {}
            for i, key in ipairs(KEYS) do
                local limit = tonumber(ARGV[2*i-1])
                local window = tonumber(ARGV[2*i])
                local slot = math.floor(now / window)
                local stored = redis.call('HMGET', key, 'slot', 'count')
                slots[i] = slot
                reset_times[i] = (slot + 1) * window
                if tonumber(stored[1]) == slot and tonumber(stored[2]) >= limit then
                    max_wait = math.max(max_wait, reset_times[i] - now)
                end
            end
            if max_wait > 0 then return max_wait end
            for i, key in ipairs(KEYS) do
                if tonumber(redis.call('HGET', key, 'slot')) ~= slots[i] then
                    redis.call('HSET', key, 'slot', slots[i], 'count', 1)
                else
                    redis.call('HINCRBY', key, 'count', 1)
                end
                redis.call('EXPIREAT', key, reset_times[i])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final OpenApiProperties properties;

    void signingIp(String ip) {
        single(prefix() + "sign:ip:" + OpenApiCredentials.digest(ip), properties.tokenIpLimit(), SIGNING_WINDOW);
    }

    void signingApp(long appId) {
        single(prefix() + "sign:app:" + appId, properties.tokenAppLimit(), SIGNING_WINDOW);
    }

    void business(long appId, int appLimit, int appWindow, long endpointId, Integer endpointLimit, Integer endpointWindow) {
        String group = "{open-api:" + properties.environment() + ":" + appId + "}:";
        String app = prefix() + group + "total:" + appWindow;
        if (endpointLimit == null && endpointWindow == null) {
            single(app, appLimit, appWindow);
            return;
        }
        if (endpointLimit == null || endpointWindow == null || endpointLimit < 1 || endpointWindow < 1) {
            throw OpenApiException.unavailableDependency();
        }
        String endpoint = prefix() + group + "endpoint:" + endpointId + ":" + endpointWindow;
        enforce(List.of(app, endpoint), String.valueOf(appLimit), String.valueOf(appWindow),
                String.valueOf(endpointLimit), String.valueOf(endpointWindow));
    }

    private String prefix() {
        return "flow:open-api:" + properties.environment() + ":rate:";
    }

    private void single(String key, int limit, int window) {
        enforce(List.of(key), String.valueOf(limit), String.valueOf(window));
    }

    private void enforce(List<String> keys, String... args) {
        Long retry = redis.execute(ACQUIRE, keys, (Object[]) args);
        if (retry == null) {
            throw OpenApiException.unavailableDependency();
        }
        if (retry > 0) {
            throw OpenApiException.limited(retry);
        }
    }
}
