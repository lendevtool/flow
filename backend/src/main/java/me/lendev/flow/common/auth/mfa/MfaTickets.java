package me.lendev.flow.common.auth.mfa;

import java.time.Duration;
import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.LoginMethod;
import me.lendev.flow.common.auth.rate.TooManyRequestsException;
import me.lendev.flow.common.exception.BadRequestException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 保存短时 MFA 挑战。凭证使用一次，重建挑战不重置账号尝试配额。
 */
@Component
@RequiredArgsConstructor
class MfaTickets {

    static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final String TICKET = "flow:auth:mfa:ticket:";
    private static final String ATTEMPTS = "flow:auth:mfa:attempts:";
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """, Long.class);
    private static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if count >= 5 then
                return math.max(1, redis.call('TTL', KEYS[1]))
            end
            if redis.call('INCR', KEYS[1]) == 1 then
                redis.call('EXPIRE', KEYS[1], 300)
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    enum Purpose {LOGIN, PROFILE}

    record Ticket(Purpose purpose, long userId, String identityKey, LoginMethod method,
                  String bindingId, String newSecret, String sessionHash) {

    }

    record Stored(String id, String value, Ticket ticket) {

    }

    String issue(Ticket ticket) {
        String id = MfaSecrets.randomCode(32);
        redis.opsForValue().set(TICKET + id, json.writeValueAsString(ticket), LIFETIME);
        return id;
    }

    Stored get(String id, Purpose purpose) {
        String value = redis.opsForValue().get(TICKET + id);
        if (value == null) {
            throw expired();
        }
        var ticket = json.readValue(value, Ticket.class);
        if (ticket.purpose() != purpose) {
            throw expired();
        }
        return new Stored(id, value, ticket);
    }

    void consume(Stored stored) {
        Long removed = redis.execute(CONSUME, List.of(TICKET + stored.id()), stored.value());
        if (removed == null) {
            throw new RedisConnectionFailureException("Cannot consume MFA challenge");
        }
        if (removed != 1) {
            throw expired();
        }
    }

    void checkAttempt(long userId) {
        Long wait = redis.execute(LIMIT, List.of(ATTEMPTS + userId));
        if (wait == null) {
            throw new RedisConnectionFailureException("Cannot check MFA attempts");
        }
        if (wait > 0) {
            throw new TooManyRequestsException(wait);
        }
    }

    static BadRequestException expired() {
        return BadRequestException.localized("error.mfa.challenge-expired");
    }
}
