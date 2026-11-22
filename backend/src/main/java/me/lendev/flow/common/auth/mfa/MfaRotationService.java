package me.lendev.flow.common.auth.mfa;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.policy.AuthPolicy;
import me.lendev.flow.policy.AuthPolicyAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理 MFA 写入版本和可重试的绑定重加密任务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MfaRotationService {

    private static final String FAILED = "FAILED";

    private final MfaRotationStore store;
    private final MfaSecrets secrets;
    private final AuthPolicyAccess policies;
    private final Clock clock;
    private final MfaKeyStore keys;

    public record Status(String activeKeyId, String targetKeyId, Instant activeSince, Instant lastCompletedAt,
                         Instant nextRotationAt, Instant reminderAt, boolean reminderDue, boolean overdue,
                         String status, String errorCode, long pendingBindings, boolean canStart) {

    }

    /**
     * 返回仅包含版本和运维状态的管理端视图。
     *
     * @return 写入版本、轮转进度和下一次轮转时间
     */
    public Status status() {
        var state = store.state(false);
        var rule = policies.current().mfa();
        var now = clock.instant();
        Instant due = nextRotation(state, rule);
        Instant reminder = due == null ? null : due.minus(rule.keyReminderDays(), ChronoUnit.DAYS);
        String target = keys.settings(false).targetKeyId();
        return new Status(state.active(), target, state.activeSince(), state.lastCompleted(), due, reminder,
                reached(now, reminder), reached(now, due), state.status(), state.error(),
                state.active() == null ? 0 : store.pendingCount(state.active()), canStart(state, target));
    }

    /**
     * 切换写入版本。到期触发和手工触发共用版本锁与审计。
     *
     * @param scheduled 是否由后台定时任务触发，自动触发会跳过未到期及失败的轮转
     * @throws BadRequestException 所需密钥版本不可用，或当前状态不允许启动手工轮转
     */
    @Audited(AuditOperation.MFA_ROTATION_START)
    @Transactional(rollbackFor = Exception.class)
    public void start(boolean scheduled) {
        var state = store.state(true);
        Instant now = clock.instant();
        String target = keys.settings(false).targetKeyId();
        if (scheduled && (FAILED.equals(state.status()) || !canStart(state, target)
                || !reached(now, nextRotation(state, policies.current().mfa())))) {
            return;
        }
        start(state, target, now);
    }

    /**
     * 在刷新事务内启用目标版本，并沿用同一套迁移校验。
     */
    void activateFromRefresh(MfaRotationStore.State state, String target) {
        start(state, target, clock.instant());
    }

    private void start(MfaRotationStore.State state, String target, Instant now) {
        if (!canStart(state, target) || store.versions().stream().anyMatch(version -> !secrets.hasKey(version))) {
            throw BadRequestException.localized("error.mfa.rotation.not-ready");
        }
        if (FAILED.equals(state.status())) {
            store.resume();
            log.info("MFA 密钥轮转已登记恢复，requestId={} keyVersion={}",
                    AuditParticipants.currentRequestId(), state.active());
        } else {
            store.activate(state, target, now);
            log.info("MFA 写入版本已切换，requestId={} keyVersion={}", AuditParticipants.currentRequestId(), target);
        }
    }

    /**
     * 在状态锁内确认所有绑定已迁移，并结束同一版本的运行中任务。
     *
     * @param active 此轮任务开始时使用的写入版本
     */
    @Audited(AuditOperation.MFA_ROTATION_COMPLETE)
    @Transactional(rollbackFor = Exception.class)
    public void complete(String active) {
        var state = store.state(true);
        if (running(state, active) && store.pendingCount(active) == 0) {
            store.complete(clock.instant());
            log.info("MFA 密钥轮转完成状态已写入，requestId={} keyVersion={}",
                    AuditParticipants.currentRequestId(), active);
        }
    }

    /**
     * 记录同一版本的运行中任务解密失败，保留新旧版本供重试。
     *
     * @param active 此轮任务开始时使用的写入版本
     */
    @Audited(AuditOperation.MFA_ROTATION_FAIL)
    @Transactional(rollbackFor = Exception.class)
    public void fail(String active) {
        if (running(store.state(true), active)) {
            store.fail();
            log.info("MFA 密钥轮转失败状态已写入，requestId={} keyVersion={}",
                    AuditParticipants.currentRequestId(), active);
        }
    }

    private boolean canStart(MfaRotationStore.State state, String target) {
        if ("RUNNING".equals(state.status())) {
            return false;
        }
        if (FAILED.equals(state.status())) {
            return secrets.hasKey(state.active());
        }
        return target != null && !state.usedVersions().contains(target) && secrets.hasKey(target);
    }

    private static boolean running(MfaRotationStore.State state, String active) {
        return "RUNNING".equals(state.status()) && active.equals(state.active());
    }

    private static boolean reached(Instant now, Instant date) {
        return date != null && !now.isBefore(date);
    }

    private static Instant nextRotation(MfaRotationStore.State state, AuthPolicy.Mfa rule) {
        return state.activeSince() == null ? null : state.activeSince().plus(rule.keyRotationDays(), ChronoUnit.DAYS);
    }
}
