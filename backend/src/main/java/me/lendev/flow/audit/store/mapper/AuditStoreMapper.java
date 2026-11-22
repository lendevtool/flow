package me.lendev.flow.audit.store.mapper;

import static me.lendev.flow.audit.persistence.table.AuditOutboxTableDef.AUDIT_OUTBOX;

import java.time.LocalDateTime;
import java.util.List;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateWrapper;
import me.lendev.flow.audit.model.AuditEvent;
import me.lendev.flow.audit.persistence.AuditOutbox;
import me.lendev.flow.audit.store.AuditStore.Delivery;
import me.lendev.flow.audit.store.AuditStore.Lease;

/**
 * 持久化审计队列，条件更新返回实际写入的行数供调用方校验。
 */
public interface AuditStoreMapper extends BaseMapper<AuditOutbox> {

    /**
     * 用已知结果改写 PREPARED 意图：替换载荷并发布为 PENDING，同时写入结果与阶段。
     *
     * @return 实际更新行数；意图已被恢复流程发布或标识不匹配时为 0
     */
    default int completePrepared(long id, AuditEvent event, Payload payload, LocalDateTime occurredAt,
                                 LocalDateTime nextRetryAt, LocalDateTime updatedAt) {
        var row = publishPrepared(payload, occurredAt, nextRetryAt, updatedAt)
                .set(AUDIT_OUTBOX.RESULT, event.result()).set(AUDIT_OUTBOX.PHASE, event.phase());
        return write(row, prepared(id).and(AUDIT_OUTBOX.EVENT_ID.eq(event.eventId()))
                .and(AUDIT_OUTBOX.OPERATION_ID.eq(event.operationId())));
    }

    /**
     * 按主键读取队列记录（含 payload）。
     *
     * @return 队列记录快照；不存在时返回 {@code null}
     */
    default Delivery find(long id) {
        var row = selectOneById(id);
        return row == null ? null : delivery(row);
    }

    /**
     * 读取记录的本轮失败次数，供退避策略计算。
     *
     * @return 失败次数；记录不存在时返回 {@code null}
     */
    default Integer findFailureCount(long id) {
        return selectObjectByQueryAs(QueryWrapper.create().select(AUDIT_OUTBOX.FAILURE_COUNT)
                .where(AUDIT_OUTBOX.ID.eq(id)), Integer.class);
    }

    /**
     * 查询到期待投递记录（PENDING/RETRY 且 nextRetryAt 已到），按到期时间升序取有界批次。
     *
     * @return 候选记录元数据列表，不含 payload
     */
    default List<Delivery> due(LocalDateTime now, int limit) {
        return selectListByQuery(metadata().where(dueAt(now))
                .orderBy(AUDIT_OUTBOX.NEXT_RETRY_AT.asc(), AUDIT_OUTBOX.ID.asc()).limit(limit))
                .stream().map(AuditStoreMapper::delivery).toList();
    }

    /**
     * 乐观领取：按 rowVersion 与到期条件将记录置为 PROCESSING，写入工作者、租约令牌与锁定期，
     * 并累加领取次数。
     *
     * @return 实际更新行数；1 表示领取成功，0 表示他人已领取或状态已变化
     */
    default int claim(Delivery candidate, String workerId, String token, LocalDateTime lockedUntil,
                      LocalDateTime updatedAt, LocalDateTime now) {
        var row = versionedPatch().set(AUDIT_OUTBOX.STATUS, "PROCESSING")
                .set(AUDIT_OUTBOX.WORKER_ID, workerId).set(AUDIT_OUTBOX.LEASE_TOKEN, token)
                .set(AUDIT_OUTBOX.LOCKED_UNTIL, lockedUntil).set(AUDIT_OUTBOX.UPDATED_AT, updatedAt)
                .set(AUDIT_OUTBOX.ATTEMPTS, AUDIT_OUTBOX.ATTEMPTS.add(1));
        return write(row, AUDIT_OUTBOX.ID.eq(candidate.id()).and(AUDIT_OUTBOX.ROW_VERSION.eq(candidate.rowVersion()))
                .and(dueAt(now)));
    }

    /**
     * 租约栅栏校验：仅当租约令牌匹配且锁定期未过时推进版本号，防止过期工作者写入终态。
     *
     * @return 实际更新行数；1 表示租约有效，0 表示租约已失效
     */
    default int fenceLease(Lease lease, LocalDateTime now) {
        return write(versionedPatch(), leased(lease).and(AUDIT_OUTBOX.LOCKED_UNTIL.gt(now)));
    }

    /**
     * 投递成功：释放租约并置为 DONE，清零本轮失败次数与最近错误信息。
     *
     * @return 实际更新行数；1 表示成功，0 表示租约已失效
     */
    default int markDone(Lease lease, LocalDateTime processedAt, LocalDateTime updatedAt) {
        var row = releaseLease("DONE", null, updatedAt)
                .set(AUDIT_OUTBOX.PROCESSED_AT, processedAt).set(AUDIT_OUTBOX.FAILURE_COUNT, 0)
                .set(AUDIT_OUTBOX.LAST_ERROR_CODE, null).set(AUDIT_OUTBOX.LAST_ERROR_MESSAGE, null);
        return write(row, leased(lease));
    }

    /**
     * 投递失败：释放租约并置为 RETRY 或 DEAD，累加失败次数并记录最近错误码与延后的下次重试时间。
     *
     * @return 实际更新行数；1 表示成功，0 表示租约已失效
     */
    default int markFailed(Lease lease, String status, LocalDateTime nextRetryAt, LocalDateTime updatedAt,
                           String errorCode) {
        var row = releaseLease(status, nextRetryAt, updatedAt)
                .set(AUDIT_OUTBOX.FAILURE_COUNT, AUDIT_OUTBOX.FAILURE_COUNT.add(1))
                .set(AUDIT_OUTBOX.LAST_ERROR_CODE, errorCode)
                .set(AUDIT_OUTBOX.LAST_ERROR_MESSAGE, "审计投递未完成，请通过事件标识排查");
        return write(row, leased(lease));
    }

    /**
     * 查询锁定期已过的 PROCESSING 记录，供过期租约恢复。
     *
     * @return 过期租约列表，按锁定期升序取有界批次
     */
    default List<Lease> expiredLeases(LocalDateTime now, int limit) {
        return selectListByQuery(QueryWrapper.create().select(AUDIT_OUTBOX.ID, AUDIT_OUTBOX.LEASE_TOKEN)
                .where(AUDIT_OUTBOX.STATUS.eq("PROCESSING")).and(AUDIT_OUTBOX.LOCKED_UNTIL.le(now))
                .orderBy(AUDIT_OUTBOX.LOCKED_UNTIL.asc(), AUDIT_OUTBOX.ID.asc()).limit(limit))
                .stream().map(row -> new Lease(row.getId(), row.getLeaseToken())).toList();
    }

    /**
     * 查询超过准备期限仍为 PREPARED 的记录，供超时恢复发布 UNKNOWN 结果。
     *
     * @return 超时准备事件元数据列表，不含 payload
     */
    default List<Delivery> expiredPrepared(LocalDateTime now, int limit) {
        return selectListByQuery(metadata().where(AUDIT_OUTBOX.STATUS.eq("PREPARED"))
                .and(AUDIT_OUTBOX.PREPARE_DEADLINE_AT.le(now))
                .orderBy(AUDIT_OUTBOX.PREPARE_DEADLINE_AT.asc(), AUDIT_OUTBOX.ID.asc()).limit(limit))
                .stream().map(AuditStoreMapper::delivery).toList();
    }

    /**
     * 超时恢复：按 rowVersion 与期限条件将 PREPARED 记录改写为 UNKNOWN 结果并发布为 PENDING。
     *
     * @return 实际更新行数；1 表示恢复成功，0 表示记录已被确认或版本已变化
     */
    default int recoverPrepared(Delivery current, Payload payload, LocalDateTime occurredAt, LocalDateTime nextRetryAt,
                                LocalDateTime updatedAt, LocalDateTime now) {
        var row = publishPrepared(payload, occurredAt, nextRetryAt, updatedAt).set(AUDIT_OUTBOX.RESULT, "UNKNOWN");
        return write(row, prepared(current.id()).and(AUDIT_OUTBOX.ROW_VERSION.eq(current.rowVersion()))
                .and(AUDIT_OUTBOX.PREPARE_DEADLINE_AT.le(now)));
    }

    /**
     * 载荷校验失败的准备事件直接置为 DEAD，清除投递与准备期限并记录 PAYLOAD_INVALID。
     *
     * @return 实际更新行数；1 表示成功，0 表示版本已变化
     */
    default int invalidatePrepared(Delivery candidate, LocalDateTime updatedAt) {
        var row = versionedPatch().set(AUDIT_OUTBOX.STATUS, "DEAD").set(AUDIT_OUTBOX.PREPARE_DEADLINE_AT, null)
                .set(AUDIT_OUTBOX.NEXT_RETRY_AT, null).set(AUDIT_OUTBOX.LAST_ERROR_CODE, "PAYLOAD_INVALID")
                .set(AUDIT_OUTBOX.LAST_ERROR_MESSAGE, "准备事件载荷校验失败").set(AUDIT_OUTBOX.UPDATED_AT, updatedAt);
        return write(row, prepared(candidate.id()).and(AUDIT_OUTBOX.ROW_VERSION.eq(candidate.rowVersion())));
    }

    /**
     * 人工重试：仅允许 RETRY/DEAD 记录按乐观版本号重置为 RETRY，清零本轮失败次数、
     * 累加人工重试次数并记录重试者与时间。
     *
     * @return 实际更新行数；1 表示重试成功，0 表示版本过期或状态不允许
     */
    default int retry(long id, long version, String actorId, LocalDateTime nextRetryAt, LocalDateTime retriedAt,
                      LocalDateTime updatedAt) {
        var row = versionedPatch().set(AUDIT_OUTBOX.STATUS, "RETRY").set(AUDIT_OUTBOX.FAILURE_COUNT, 0)
                .set(AUDIT_OUTBOX.MANUAL_RETRY_COUNT, AUDIT_OUTBOX.MANUAL_RETRY_COUNT.add(1))
                .set(AUDIT_OUTBOX.NEXT_RETRY_AT, nextRetryAt).set(AUDIT_OUTBOX.LAST_RETRIED_BY, actorId)
                .set(AUDIT_OUTBOX.LAST_RETRIED_AT, retriedAt).set(AUDIT_OUTBOX.UPDATED_AT, updatedAt);
        return write(row, AUDIT_OUTBOX.ID.eq(id).and(AUDIT_OUTBOX.ROW_VERSION.eq(version))
                .and(AUDIT_OUTBOX.STATUS.in("RETRY", "DEAD")));
    }

    private int write(UpdateWrapper<AuditOutbox> row, QueryCondition condition) {
        return updateByQuery(row.toEntity(), QueryWrapper.create().where(condition));
    }

    private static QueryCondition dueAt(LocalDateTime now) {
        return AUDIT_OUTBOX.STATUS.in("PENDING", "RETRY").and(AUDIT_OUTBOX.NEXT_RETRY_AT.le(now));
    }

    private static QueryCondition leased(Lease lease) {
        return AUDIT_OUTBOX.ID.eq(lease.id()).and(AUDIT_OUTBOX.STATUS.eq("PROCESSING"))
                .and(AUDIT_OUTBOX.LEASE_TOKEN.eq(lease.token(), true));
    }

    private static QueryCondition prepared(long id) {
        return AUDIT_OUTBOX.ID.eq(id).and(AUDIT_OUTBOX.STATUS.eq("PREPARED"));
    }

    private static UpdateWrapper<AuditOutbox> versionedPatch() {
        return UpdateWrapper.of(AuditOutbox.class).set(AUDIT_OUTBOX.ROW_VERSION, AUDIT_OUTBOX.ROW_VERSION.add(1));
    }

    private static UpdateWrapper<AuditOutbox> publishPrepared(Payload payload, LocalDateTime occurredAt,
                                                              LocalDateTime nextRetryAt, LocalDateTime updatedAt) {
        return versionedPatch().set(AUDIT_OUTBOX.PAYLOAD, payload.text()).set(AUDIT_OUTBOX.PAYLOAD_BYTES, payload.bytes())
                .set(AUDIT_OUTBOX.PAYLOAD_HASH, payload.hash()).set(AUDIT_OUTBOX.OCCURRED_AT, occurredAt)
                .set(AUDIT_OUTBOX.STATUS, "PENDING").set(AUDIT_OUTBOX.PREPARE_DEADLINE_AT, null)
                .set(AUDIT_OUTBOX.NEXT_RETRY_AT, nextRetryAt).set(AUDIT_OUTBOX.UPDATED_AT, updatedAt);
    }

    private static UpdateWrapper<AuditOutbox> releaseLease(String status, LocalDateTime nextRetryAt, LocalDateTime updatedAt) {
        return versionedPatch().set(AUDIT_OUTBOX.STATUS, status).set(AUDIT_OUTBOX.WORKER_ID, null)
                .set(AUDIT_OUTBOX.LEASE_TOKEN, null).set(AUDIT_OUTBOX.LOCKED_UNTIL, null)
                .set(AUDIT_OUTBOX.NEXT_RETRY_AT, nextRetryAt).set(AUDIT_OUTBOX.UPDATED_AT, updatedAt);
    }

    private static QueryWrapper metadata() {
        return QueryWrapper.create().select(AUDIT_OUTBOX.ID, AUDIT_OUTBOX.EVENT_ID, AUDIT_OUTBOX.OPERATION_ID,
                AUDIT_OUTBOX.STATUS, AUDIT_OUTBOX.ROW_VERSION, AUDIT_OUTBOX.PAYLOAD_BYTES, AUDIT_OUTBOX.PAYLOAD_HASH,
                AUDIT_OUTBOX.ATTEMPTS, AUDIT_OUTBOX.FAILURE_COUNT, AUDIT_OUTBOX.MANUAL_RETRY_COUNT);
    }

    private static Delivery delivery(AuditOutbox row) {
        return new Delivery(row.getId(), row.getEventId(), row.getOperationId(), row.getStatus(), row.getRowVersion(),
                row.getPayloadBytes(), row.getPayloadHash(), row.getPayload(), row.getAttempts(), row.getFailureCount(),
                row.getManualRetryCount());
    }

    /**
     * 序列化后的 UTF-8 载荷及其字节数与 SHA-256 哈希。
     */
    record Payload(String text, long bytes, String hash) {

    }
}
