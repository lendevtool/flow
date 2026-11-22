package me.lendev.flow.openapi.admin;

import static me.lendev.flow.audit.AuditOperation.*;
import static me.lendev.flow.openapi.admin.OpenApiAdminMapperSupport.*;
import static me.lendev.flow.openapi.admin.OpenApiAdminModels.*;
import static me.lendev.flow.openapi.admin.mapper.OpenApiAdminMapper.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.audit.model.AuditChange;
import me.lendev.flow.openapi.OpenApiCredentials;
import me.lendev.flow.openapi.admin.OpenApiAdminAudit.Mutation;
import me.lendev.flow.openapi.admin.mapper.OpenApiAdminMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 凭据轮转以同一个应用行锁串行化，查询和审计只接触状态元数据。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class OpenApiAdminSecrets {

    private static final int CANDIDATE_HOURS = 24;
    private static final int ACTIVE_DAYS = 90;
    private static final int RETIRING_HOURS = 24;

    private final OpenApiAdminService apps;
    private final OpenApiAdminMapper mapper;
    private final OpenApiAdminAudit audit;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<SecretMetadata> secrets(long appId) {
        apps.requireApp(appId, false);
        return mapper.secrets(appId).stream().map(OpenApiAdminSecrets::metadata).toList();
    }

    /**
     * 清理过期轮转版本后生成候选。凭据须待本次写入和审计提交后由控制器交付。
     *
     * @param appId 应用 ID
     * @param actor 操作用户 ID
     * @return 候选凭据及到期时间，明文仅供本次响应交付
     */
    @Audited(OPEN_API_SECRET_GENERATE)
    @Transactional(rollbackFor = Exception.class)
    public Mutation<GeneratedSecret> generate(long appId, int actor) {
        apps.requireApp(appId, true);
        LocalDateTime now = now(clock);
        var changes = new ArrayList<AuditChange>();
        for (SecretRow row : mapper.lockRotationSecrets(appId)) {
            if (row.status() == SecretStatus.ACTIVE) {
                continue;
            }
            if (row.expiresAt().isAfter(now)) {
                log.info("开放 API 凭据轮转正在进行，requestId={} appId={} version={} status={}",
                        AuditParticipants.currentRequestId(), appId, row.versionNo(), row.status());
                throw OpenApiAdminFailure.rotationInProgress();
            }
            var expired = revoked(row, now, row.status() == SecretStatus.CANDIDATE);
            changes.add(write(appId, row, expired, actor, now));
        }
        Integer latest = mapper.lockLatestSecretVersion(appId);
        int version = latest == null ? 1 : Math.incrementExact(latest);
        LocalDateTime expiresAt = now.plusHours(CANDIDATE_HOURS);
        String secret = OpenApiCredentials.newSecret();
        requireWrite(mapper.insertSecret(appId, version, OpenApiCredentials.secretHash(secret), expiresAt, actor, now));
        var created = new SecretRow(version, SecretStatus.CANDIDATE, expiresAt, null, null, null, now);
        changes.add(audit.secret(appId, null, created));
        log.info("开放 API 候选凭据元数据已写入，requestId={} appId={} version={}",
                AuditParticipants.currentRequestId(), appId, version);
        return new Mutation<>(new GeneratedSecret(version, secret, instant(expiresAt)), List.copyOf(changes));
    }

    /**
     * 确认候选已验证可用，同时为旧主用凭据设置有界过渡期。
     *
     * @param appId     应用 ID
     * @param versionNo 待激活的凭据版本
     * @param actor     操作用户 ID
     * @return 激活后的状态与被替换版本，重复确认有效版本时返回原状态
     */
    @Audited(OPEN_API_SECRET_ACTIVATE)
    @Transactional(rollbackFor = Exception.class)
    public Mutation<SecretActivation> activate(long appId, int versionNo, int actor) {
        apps.requireApp(appId, true);
        LocalDateTime now = now(clock);
        SecretRow before = requireSecret(appId, versionNo);
        if (!before.expiresAt().isAfter(now)) {
            throw OpenApiAdminFailure.secretStateConflict();
        }
        if (before.status() == SecretStatus.ACTIVE || before.status() == SecretStatus.RETIRING) {
            return Mutation.unchanged(new SecretActivation(metadata(before), null));
        }
        if (before.status() != SecretStatus.CANDIDATE) {
            throw OpenApiAdminFailure.secretStateConflict();
        }
        var changes = new ArrayList<AuditChange>();
        SecretRow replaced = retireActive(appId, before, actor, now, changes);
        var activated = new SecretRow(versionNo, SecretStatus.ACTIVE, now.plusDays(ACTIVE_DAYS), now, null, null,
                before.createdAt());
        changes.add(write(appId, before, activated, actor, now));
        log.info("开放 API 凭据激活状态已写入，requestId={} appId={} version={} retiringVersion={}",
                AuditParticipants.currentRequestId(), appId, versionNo, replaced == null ? null : replaced.versionNo());
        return new Mutation<>(new SecretActivation(metadata(activated), replaced == null ? null : metadata(replaced)),
                List.copyOf(changes));
    }

    private SecretRow retireActive(long appId, SecretRow candidate, int actor, LocalDateTime now,
                                   List<AuditChange> changes) {
        SecretRow current = null;
        for (SecretRow row : mapper.lockRotationSecrets(appId)) {
            if (row.versionNo() != candidate.versionNo() && row.status() != SecretStatus.ACTIVE) {
                throw OpenApiAdminFailure.secretStateConflict();
            }
            if (row.status() == SecretStatus.ACTIVE) {
                if (current != null) {
                    throw OpenApiAdminFailure.secretStateConflict();
                }
                current = row;
            }
        }
        if (current == null) {
            return null;
        }
        LocalDateTime graceEnd = now.plusHours(RETIRING_HOURS);
        LocalDateTime end = current.expiresAt().isBefore(graceEnd) ? current.expiresAt() : graceEnd;
        var replaced = new SecretRow(current.versionNo(), SecretStatus.RETIRING, end, current.activatedAt(),
                current.revokedAt(), current.tokensRevokedAt(), current.createdAt());
        changes.add(write(appId, current, replaced, actor, now));
        return replaced;
    }

    /**
     * 正常退役只停止签发。废弃候选和应急吊销也会让已发令牌失效。
     *
     * @param appId     应用 ID
     * @param versionNo 目标凭据版本
     * @param mode      退役、废弃候选或应急吊销
     * @param actor     操作用户 ID
     * @return 更新后的凭据状态和审计变更
     */
    @Audited(OPEN_API_SECRET_REVOKE)
    @Transactional(rollbackFor = Exception.class)
    public Mutation<SecretMetadata> revoke(long appId, int versionNo, RevokeMode mode, int actor) {
        apps.requireApp(appId, true);
        SecretRow before = requireSecret(appId, versionNo);
        LocalDateTime now = now(clock);
        SecretRow after = switch (mode) {
            case RETIRE -> retire(before, now);
            case ABANDON -> abandon(before, now);
            case COMPROMISED -> before.tokensRevokedAt() == null ? revoked(before, now, true) : before;
        };
        if (before == after) {
            return Mutation.unchanged(metadata(before));
        }
        AuditChange change = write(appId, before, after, actor, now);
        log.info("开放 API 凭据撤销状态已写入，requestId={} appId={} version={} mode={}",
                AuditParticipants.currentRequestId(), appId, versionNo, mode);
        return Mutation.changed(metadata(after), change);
    }

    private static SecretRow retire(SecretRow row, LocalDateTime now) {
        if (row.status() == SecretStatus.REVOKED && row.activatedAt() != null && row.tokensRevokedAt() == null) {
            return row;
        }
        if (row.status() != SecretStatus.RETIRING) {
            throw OpenApiAdminFailure.secretStateConflict();
        }
        return revoked(row, now, false);
    }

    private static SecretRow abandon(SecretRow row, LocalDateTime now) {
        if (row.status() != SecretStatus.CANDIDATE) {
            throw OpenApiAdminFailure.secretStateConflict();
        }
        return revoked(row, now, true);
    }

    private static SecretRow revoked(SecretRow row, LocalDateTime now, boolean invalidateTokens) {
        LocalDateTime revokedAt = row.revokedAt() == null ? now : row.revokedAt();
        LocalDateTime tokensRevokedAt = invalidateTokens && row.tokensRevokedAt() == null ? now : row.tokensRevokedAt();
        return new SecretRow(row.versionNo(), SecretStatus.REVOKED, row.expiresAt(), row.activatedAt(), revokedAt,
                tokensRevokedAt, row.createdAt());
    }

    private SecretRow requireSecret(long appId, int versionNo) {
        SecretRow row = mapper.lockSecret(appId, versionNo);
        if (row == null) {
            throw OpenApiAdminFailure.notFound();
        }
        return row;
    }

    private AuditChange write(long appId, SecretRow before, SecretRow after, int actor, LocalDateTime now) {
        requireWrite(mapper.updateSecret(appId, after, actor, now));
        return audit.secret(appId, before, after);
    }

    private static SecretMetadata metadata(SecretRow row) {
        return new SecretMetadata(row.versionNo(), row.status(), instant(row.expiresAt()), instant(row.activatedAt()),
                instant(row.revokedAt()), instant(row.tokensRevokedAt()), instant(row.createdAt()));
    }
}
