package me.lendev.flow.common.auth;

import cn.dev33.satoken.exception.NotLoginException;
import cn.hutool.crypto.digest.DigestUtil;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.dev33.satoken.stp.parameter.enums.SaReplacedRange;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import me.lendev.flow.common.exception.BizException;
import me.lendev.flow.common.auth.mfa.MfaStore;
import me.lendev.flow.common.auth.mfa.MfaService;

import java.util.Objects;

import me.lendev.flow.common.response.BizResponseCode;
import me.lendev.flow.pms.domain.entity.User;
import me.lendev.flow.policy.AuthPolicy;
import me.lendev.flow.policy.AuthPolicyAccess;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 签发和校验会话。绝对时长独立于业务活动，Redis 原子更新空闲时长。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionRules {

    private static final String MFA_BINDING = "mfaBinding";
    private static final String MFA_RECOVERY_AT = "mfaRecoveryAt";
    private static final String LOGIN_METHOD = "loginMethod";
    private static final String RESTRICTED = "passwordChangeRequired";
    private static final String PREFIX = "flow:auth:activity:";
    private static final DefaultRedisScript<Long> CREATE = new DefaultRedisScript<>("""
            redis.replicate_commands()
            local now = redis.call('TIME')
            local ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
            local absolute = tonumber(ARGV[1])
            local idle = tonumber(ARGV[2])
            redis.call('HSET', KEYS[1], 'deadline', ms + absolute, 'idle', idle)
            redis.call('PEXPIRE', KEYS[1], math.min(absolute, idle))
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> ACTIVITY = new DefaultRedisScript<>("""
            local limits = redis.call('HMGET', KEYS[1], 'deadline', 'idle')
            if not limits[1] then return 0 end
            redis.replicate_commands()
            local now = redis.call('TIME')
            local ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
            local remaining = tonumber(limits[1]) - ms
            if remaining <= 0 then return 0 end
            if ARGV[1] == 'touch' then
                redis.call('PEXPIRE', KEYS[1], math.min(remaining, tonumber(limits[2])))
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final AuthPolicyAccess policies;
    private final MfaStore mfa;
    private final Clock clock;

    /**
     * 签发会话并保存独立的空闲期限。临时密码过期时间也约束该会话。
     *
     * @param user     已验证凭据的用户
     * @param role     本次会话的有效角色
     * @param policy   本次认证使用的策略快照
     * @param method   凭据来源，用于识别是否适用本地密码规则
     * @param mfaProof 双因素校验结果，本次登录未执行双因素校验时为 {@code null}
     * @return 是否需要先修改密码才能访问受保护接口
     * @throws BizException        本地临时密码已过期
     * @throws DataAccessException 无法初始化会话活动状态
     */
    public boolean start(User user, String role, AuthPolicy policy, LoginMethod method, MfaService.Proof mfaProof) {
        Instant now = clock.instant();
        boolean local = method != LoginMethod.DOMAIN;
        if (local && temporaryPasswordExpired(user, now)) {
            throw new BizException(BizResponseCode.ERR_10002);
        }
        boolean restricted = local && requiresPasswordChange(user, policy, now);
        long absolute = policy.session().absoluteTimeoutSeconds();
        if (local && user.getTemporaryPasswordExpiresAt() != null) {
            long remainingMs = user.getTemporaryPasswordExpiresAt().toInstant(ZoneOffset.UTC).toEpochMilli() - now.toEpochMilli();
            absolute = Math.clamp((remainingMs + 999) / 1000, 1, absolute);
        }
        StpUtil.login(user.getId(), SaLoginParameter.create().setTimeout(absolute)
                .setIsConcurrent(policy.session().allowConcurrent()).setIsShare(false)
                .setReplacedRange(SaReplacedRange.ALL_DEVICE_TYPE).setRightNowCreateTokenSession(true));
        String token = StpUtil.getTokenValue();
        try {
            var result = redis.execute(CREATE, List.of(activityKey(token)), Long.toString(absolute * 1000),
                    Long.toString(policy.session().idleTimeoutSeconds() * 1000));
            if (!Long.valueOf(1).equals(result)) {
                throw new RedisConnectionFailureException("Cannot initialize session activity");
            }
            var session = StpUtil.getTokenSession();
            session.set(SaTokenConfigure.CURRENT_ROLE_KEY, role)
                    .set(AccountSession.IDENTITY_KEY, user.getIdentityKey()).set(RESTRICTED, restricted)
                    .set(LOGIN_METHOD, method.name());
            if (mfaProof != null) {
                session.set(MFA_BINDING, mfaProof.bindingId());
                if (mfaProof.recovered()) {
                    session.set(MFA_RECOVERY_AT, now.getEpochSecond());
                }
            }
            session.updateTimeout(absolute);
        } catch (RuntimeException error) {
            try {
                StpUtil.logout();
            } catch (RuntimeException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
        return restricted;
    }

    /**
     * 验证空闲状态和换密限制，返回已校验的令牌供成功请求记录活动。
     *
     * @param user                        当前登录用户
     * @param allowedDuringPasswordChange 当前接口是否允许必须换密的会话使用
     * @return 当前会话令牌
     * @throws NotLoginException 会话超期或 MFA 绑定已失效
     * @throws BizException      必须换密的会话尝试访问受保护接口
     */
    public String check(User user, boolean allowedDuringPasswordChange) {
        String token = StpUtil.getTokenValue();
        Instant now = clock.instant();
        if (!activity(token, false)) {
            throw expired(token);
        }
        var session = StpUtil.getStpLogic().getTokenSession(false);
        if (session == null) {
            throw expired(token);
        }
        var policy = policies.current();
        var state = mfa.state(user.getId());
        if ((policy.mfa().required() || state.enabled())
                && (!state.bound() || !Objects.equals(state.bindingId(), session.getString(MFA_BINDING)))) {
            throw expired(token);
        }
        if (LoginMethod.DOMAIN.name().equals(session.getString(LOGIN_METHOD))) {
            return token;
        }
        if (temporaryPasswordExpired(user, now)) {
            throw expired(token);
        }
        boolean restricted = Boolean.TRUE.equals(session.get(RESTRICTED));
        if (!restricted && requiresPasswordChange(user, policy, now)) {
            throw expired(token);
        }
        if (restricted && !allowedDuringPasswordChange) {
            throw new BizException(BizResponseCode.PASSWORD_CHANGE_REQUIRED);
        }
        return token;
    }

    /**
     * 恢复码登录后的五分钟内，允许同一会话直接更换设备。
     */
    public static boolean recoveredRecently(String bindingId, Instant now) {
        if (bindingId == null || StpUtil.getTokenValue() == null) {
            return false;
        }
        var session = StpUtil.getStpLogic().getTokenSession(false);
        if (session == null || !bindingId.equals(session.getString(MFA_BINDING))
                || !(session.get(MFA_RECOVERY_AT) instanceof Number timestamp)) {
            return false;
        }
        long elapsed = now.getEpochSecond() - timestamp.longValue();
        return elapsed >= 0 && elapsed < 300;
    }

    /**
     * 旧会话没有凭据来源字段，按本地登录处理。
     */
    public static LoginMethod currentLoginMethod() {
        if (StpUtil.getTokenValue() == null) {
            return LoginMethod.LOCAL;
        }
        var session = StpUtil.getStpLogic().getTokenSession(false);
        return session != null && LoginMethod.DOMAIN.name().equals(session.getString(LOGIN_METHOD))
                ? LoginMethod.DOMAIN : LoginMethod.LOCAL;
    }

    /**
     * 刷新会话的空闲时长，最长续至会话的绝对截止时间。Redis 暂时不可用时保留业务响应。
     *
     * @param token 由 {@link #check(User, boolean)} 校验过的会话令牌
     */
    public void recordActivity(String token) {
        try {
            activity(token, true);
        } catch (DataAccessException exception) {
            // 业务操作已完成，保留其响应。后续请求仍会验证实际的会话期限。
            log.warn("会话活动时间未写入，本次业务响应保留，会话可能按原空闲期限到期，请检查 Redis 写入与连通性，errorType={}",
                    exception.getClass().getSimpleName());
        }
    }

    public void logout() {
        String token = StpUtil.getTokenValue();
        StpUtil.logout();
        redis.delete(activityKey(token));
    }

    private static String activityKey(String token) {
        return PREFIX + DigestUtil.sha256Hex(token);
    }

    private boolean activity(String token, boolean touch) {
        Long result = redis.execute(ACTIVITY, List.of(activityKey(token)), touch ? "touch" : "check");
        if (result == null) {
            throw new RedisConnectionFailureException("Cannot check session activity");
        }
        return result == 1;
    }

    private static boolean temporaryPasswordExpired(User user, Instant now) {
        return user.getTemporaryPasswordExpiresAt() != null
                && !now.isBefore(user.getTemporaryPasswordExpiresAt().toInstant(ZoneOffset.UTC));
    }

    private static boolean requiresPasswordChange(User user, AuthPolicy policy, Instant now) {
        if (Boolean.TRUE.equals(user.getPasswordChangeRequired())) {
            return true;
        }
        long maxAge = policy.password().maxAgeSeconds();
        return maxAge > 0 && user.getPasswordChangedAt() != null
                && !now.isBefore(user.getPasswordChangedAt().toInstant(ZoneOffset.UTC).plusSeconds(maxAge));
    }

    private static NotLoginException expired(String token) {
        return NotLoginException.newInstance(StpUtil.getLoginType(), NotLoginException.INVALID_TOKEN, "登录凭证已失效", token);
    }
}
