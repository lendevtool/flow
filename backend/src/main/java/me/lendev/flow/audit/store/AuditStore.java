package me.lendev.flow.audit.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import com.mybatisflex.core.row.RowMapperInvoker;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditProperties;
import me.lendev.flow.audit.model.*;
import me.lendev.flow.audit.persistence.AuditOutbox;
import me.lendev.flow.audit.persistence.OperationChange;
import me.lendev.flow.audit.persistence.OperationLog;
import me.lendev.flow.audit.query.mapper.AuditQueryMapper;
import me.lendev.flow.audit.store.mapper.AuditStoreMapper;
import me.lendev.flow.audit.store.mapper.AuditStoreMapper.Payload;
import me.lendev.flow.audit.store.mapper.OperationChangeMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 同数据源的持久化审计队列（outbox）：生产者绝不在业务事务内直接调用消费者， 二者仅通过队列记录交接。
 */
@Repository
@Slf4j
public class AuditStore {

    private final AuditStoreMapper mapper;
    private final AuditQueryMapper logs;
    private final RowMapperInvoker batch;
    private final ObjectMapper json;
    private final AuditProperties properties;
    private final Clock clock;
    private final TransactionTemplate independent;
    private final String workerId = UUID.randomUUID().toString();

    public AuditStore(AuditStoreMapper mapper, AuditQueryMapper logs, SqlSessionFactory sqlSessions, ObjectMapper json,
                      PlatformTransactionManager manager, AuditProperties properties, Clock clock) {
        this.mapper = mapper;
        this.logs = logs;
        this.batch = new RowMapperInvoker(sqlSessions);
        this.json = json;
        this.properties = properties;
        this.clock = clock;
        this.independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * outbox 队列记录快照；payload 仅在按条读取时携带。
     */
    public record Delivery(long id, String eventId, String operationId, String status, long rowVersion,
                           long payloadBytes, String payloadHash, String payload,
                           int attempts, int failureCount, int manualRetryCount) {

    }

    /**
     * 投递租约：主键加一次性令牌，用于并发领取后的独占校验。
     */
    public record Lease(long id, String token) {

    }

    /**
     * 将 UTC 瞬时时间截断到毫秒并转为 {@link LocalDateTime}，供持久化列绑定使用。
     *
     * @param value 瞬时时间，{@code null} 原样返回
     * @return 毫秒精度的 UTC 本地时间，入参为 {@code null} 时返回 {@code null}
     */
    public static LocalDateTime time(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    /**
     * @return 统一时钟的当前时间，截断到毫秒
     */
    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("审计写入必须处于可写业务事务中");
        }
    }

    /**
     * 在当前可写业务事务内将成功事件加入 outbox，状态为 PENDING 并立即可投递。
     *
     * @param event 已冻结的审计事件
     * @return outbox 记录主键
     * @throws IllegalStateException 当前不处于可写业务事务时抛出
     */
    public long enqueue(AuditEvent event) {
        requireTransaction();
        return insertEvent(event, false);
    }

    /**
     * 在独立新事务（REQUIRES_NEW）中持久化外部操作意图； 该提交必须先于任何非事务性业务副作用完成。
     *
     * @param event 意图事件
     * @return outbox 记录主键
     */
    public long prepare(AuditEvent event) {
        return independent.execute(status -> insertEvent(event, true));
    }

    /**
     * 在当前业务事务内持久化意图：事务回滚则意图随之消失， 外部步骤仅在本事务提交后才会启动。
     *
     * @param event 意图事件
     * @return outbox 记录主键
     */
    public long prepareInTransaction(AuditEvent event) {
        requireTransaction();
        return insertEvent(event, true);
    }

    /**
     * 在独立新事务中记录已完成事务后的失败尝试，仅限 ATTEMPT 阶段事件。
     *
     * @param event 失败尝试事件，phase 必须为 ATTEMPT
     * @throws IllegalArgumentException 事件阶段不是 ATTEMPT 时抛出
     */
    public void recordAttempt(AuditEvent event) {
        if (!"ATTEMPT".equals(event.phase())) {
            throw new IllegalArgumentException("仅供已完成事务后的失败尝试记录");
        }
        independent.executeWithoutResult(status -> insertEvent(event, false));
    }

    private long insertEvent(AuditEvent event, boolean prepared) {
        Payload payload = encode(event);
        Instant now = now();
        var row = new AuditOutbox();
        row.setEventId(event.eventId());
        row.setOperationId(event.operationId());
        row.setRequestId(event.requestId());
        row.setTraceId(event.traceId());
        row.setBatchId(event.batchId());
        row.setSchemaVersion(event.schemaVersion());
        row.setOccurredAt(time(event.occurredAt()));
        row.setModuleCode(event.moduleCode());
        row.setOperationCode(event.operationCode());
        row.setPhase(event.phase());
        row.setResult(event.result());
        row.setPayload(payload.text());
        row.setPayloadBytes(payload.bytes());
        row.setPayloadHash(payload.hash());
        row.setStatus(prepared ? "PREPARED" : "PENDING");
        row.setNextRetryAt(prepared ? null : time(now));
        row.setPrepareDeadlineAt(prepared ? time(now.plusSeconds(properties.getPrepareTimeoutSeconds())) : null);
        row.setCreatedAt(time(now));
        row.setUpdatedAt(time(now));
        return requireGeneratedId(mapper.insertSelective(row), row.getId());
    }

    /**
     * 在独立新事务中用已知结果改写 PREPARED 意图并发布为待投递事件。
     *
     * <p>若超时恢复已先行发布 UNKNOWN 结果（更新 0 行），不得改写既成历史，
     * 改为追加一条携带新 eventId 的结果事件。
     *
     * @param id          outbox 中意图记录的主键
     * @param knownResult 已确认的结果事件，eventId/operationId 须与意图一致
     * @throws AuditPayloadException 意图与结果事件标识不一致时抛出
     */
    public void completePrepared(long id, AuditEvent knownResult) {
        independent.executeWithoutResult(status -> {
            Payload payload = encode(knownResult);
            int changed = mapper.completePrepared(id, knownResult, payload, time(knownResult.occurredAt()), time(now()), time(now()));
            if (changed == 0) {
                // 超时恢复可能已先行发布 UNKNOWN 结果，不得改写既成历史；此时改为追加一条新事件。
                Delivery original = find(id);
                if (!original.eventId().equals(knownResult.eventId()) || !original.operationId().equals(knownResult.operationId())) {
                    throw new AuditPayloadException();
                }
                log.info("准备事件已被恢复流程发布，追加结果事件，事件队列ID={} 原eventId={}", id, original.eventId());
                insertEvent(knownResult.toBuilder().eventId(UUID.randomUUID().toString()).build(), false);
            }
        });
    }

    /**
     * 按主键读取队列记录（含 payload）。
     *
     * @param id outbox 记录主键
     * @return 队列记录快照
     * @throws EmptyResultDataAccessException 记录不存在时抛出
     */
    public Delivery find(long id) {
        return requireRow(mapper.find(id));
    }

    private static <T> T requireRow(T row) {
        if (row == null) {
            throw new EmptyResultDataAccessException(1);
        }
        return row;
    }

    /**
     * 查询到期待投递记录的有界元数据；大载荷仅在单条领取成功后才加载。
     *
     * @return 待投递候选列表（不含 payload）
     */
    public List<Delivery> due() {
        return mapper.due(time(now()), properties.getBatchSize());
    }

    /**
     * 基于 rowVersion 乐观并发控制领取一条到期记录，成功后进入 PROCESSING 并持有租约。
     *
     * @param candidate 到期候选记录（不含 payload）
     * @return 领取成功的租约；他人已领取或状态已变化时返回 {@code null}
     */
    public Lease claim(Delivery candidate) {
        // 基于 rowVersion 的乐观并发控制：他人已领取或状态已变化时更新 0 行，返回 null 表示领取失败。
        String token = UUID.randomUUID().toString();
        int changed = mapper.claim(candidate, workerId, token, time(now().plusSeconds(properties.getLeaseSeconds())),
                time(now()), time(now()));
        return changed == 1 ? new Lease(candidate.id(), token) : null;
    }

    /**
     * 在独立新事务中投递一条已领取记录：栅栏校验租约、解码并校验载荷、幂等写入
     * 历史摘要与对象明细，最后标记 DONE。
     *
     * @param lease 领取成功的投递租约
     * @throws IllegalStateException 租约已失效时抛出
     * @throws AuditPayloadException 载荷校验失败或与已入历史的哈希不一致时抛出
     */
    public void consume(Lease lease) {
        independent.executeWithoutResult(status -> {
            // 任何终态历史写入之前，先对有效租约做栅栏校验（fence token）并推进版本号。
            int fenced = mapper.fenceLease(lease, time(now()));
            if (fenced != 1) {
                throw new IllegalStateException("审计投递租约已失效");
            }
            Delivery delivery = find(lease.id());
            AuditEvent event = decode(delivery);
            String existingHash = logs.loggedPayloadHash(event.eventId());
            if (existingHash != null) {
                // 幂等保护：同一 eventId 已入历史表时，仅当载荷哈希一致才允许跳过重复写入。
                if (!delivery.payloadHash().equals(existingHash)) {
                    throw new AuditPayloadException();
                }
            } else {
                long logId = insertSummary(event, delivery);
                insertChanges(logId, event);
            }
            int done = mapper.markDone(lease, time(now()), time(now()));
            if (done != 1) {
                throw new IllegalStateException("审计投递租约已失效");
            }
        });
    }

    /**
     * 记录一次投递失败：累加失败次数，永久失败或达到上限时置为 DEAD，
     * 否则置为 RETRY 并按失败次数退避延后下次投递。
     *
     * @param lease     投递租约
     * @param errorCode 稳定的失败分类码
     * @param permanent 是否为永久失败（如载荷无效），true 时直接置为 DEAD
     */
    public void failed(Lease lease, String errorCode, boolean permanent) {
        int failures = requireRow(mapper.findFailureCount(lease.id())) + 1;
        boolean dead = permanent || failures >= properties.getMaxFailures();
        mapper.markFailed(lease, dead ? "DEAD" : "RETRY", dead ? null : time(now().plusSeconds(backoff(failures))),
                time(now()), errorCode);
    }

    private long backoff(int failures) {
        return switch (failures) {
            case 1 -> 5;
            case 2 -> 30;
            case 3 -> 120;
            case 4 -> 600;
            case 5 -> 1800;
            default -> 3600;
        };
    }

    /**
     * 恢复过期租约：将锁定期已过的 PROCESSING 记录按 LEASE_EXPIRED 失败处理，重新进入重试。
     */
    public void recoverExpired() {
        List<Lease> expired = mapper.expiredLeases(time(now()), properties.getBatchSize());
        expired.forEach(lease -> failed(lease, "LEASE_EXPIRED", false));
        if (!expired.isEmpty()) {
            log.info("已恢复过期投递租约，数量={}", expired.size());
        }
    }

    /**
     * 恢复超时准备事件：将超过准备期限仍为 PREPARED 的记录发布为 UNKNOWN 结果进入投递；
     * 载荷校验失败的记录直接置为 DEAD。
     */
    public void recoverPrepared() {
        List<Delivery> expired = mapper.expiredPrepared(time(now()), properties.getBatchSize());
        for (Delivery candidate : expired) {
            try {
                independent.executeWithoutResult(status -> {
                    Delivery current = find(candidate.id());
                    if (!"PREPARED".equals(current.status()) || current.rowVersion() != candidate.rowVersion()) {
                        return;
                    }
                    AuditEvent unknown = decode(current).toBuilder().result("UNKNOWN").durationMs(null)
                            .occurredAt(now()).errorCode("OUTCOME_UNKNOWN").errorMessage("外部操作结果未能确认").build();
                    int changed = mapper.recoverPrepared(current, encode(unknown), time(now()), time(now()), time(now()), time(now()));
                    if (changed == 1) {
                        log.warn("外部审计意图超时，已将 UNKNOWN 结果加入投递，请按操作 ID 核对外部系统是否已经执行，outboxId={} eventId={} operationId={} errorCode=OUTCOME_UNKNOWN",
                                current.id(), current.eventId(), current.operationId());
                    }
                });
            } catch (AuditPayloadException exception) {
                int changed = mapper.invalidatePrepared(candidate, time(now()));
                if (changed == 1) {
                    log.warn("准备事件载荷校验失败且已标记 DEAD，请检查审计存储完整性和事件格式，outboxId={} eventId={} operationId={} payloadBytes={} errorType={}",
                            candidate.id(), candidate.eventId(), candidate.operationId(), candidate.payloadBytes(),
                            exception.getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * 在当前可写业务事务内人工重试一条 RETRY/DEAD 记录：按乐观版本号重置状态并清零本轮失败次数。
     *
     * @param id      outbox 记录主键
     * @param version 调用方持有的行版本号
     * @param actorId 发起重试的操作者标识，不可为 {@code null}
     * @return 重试成功返回 true；版本过期或状态不允许重试时返回 false
     */
    public boolean retry(long id, long version, String actorId) {
        requireTransaction();
        return mapper.retry(id, version, Objects.requireNonNull(actorId), time(now()), time(now()), time(now())) == 1;
    }

    private Payload encode(AuditEvent event) {
        validate(event);
        String payload = json.writeValueAsString(event);
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.getMaxPayloadBytes()) {
            throw new IllegalArgumentException("审计事件过大，请分批操作");
        }
        return new Payload(payload, bytes.length, hash(bytes));
    }

    private AuditEvent decode(Delivery delivery) {
        try {
            byte[] bytes = delivery.payload().getBytes(StandardCharsets.UTF_8);
            if (bytes.length != delivery.payloadBytes() || !hash(bytes).equals(delivery.payloadHash())) {
                throw new AuditPayloadException();
            }
            AuditEvent event = json.readValue(delivery.payload(), AuditEvent.class);
            validate(event);
            if (!event.eventId().equals(delivery.eventId()) || !event.operationId().equals(delivery.operationId())) {
                throw new AuditPayloadException();
            }
            return event;
        } catch (RuntimeException exception) {
            throw new AuditPayloadException();
        }
    }

    private void validate(AuditEvent event) {
        try {
            if (event.schemaVersion() != 1 || !UUID.fromString(event.eventId()).toString().equals(event.eventId())
                    || !UUID.fromString(event.operationId()).toString().equals(event.operationId())
                    || event.startedAt() == null || event.occurredAt() == null || event.actor() == null
                    || event.actor().name() == null || event.moduleCode() == null || event.operationCode() == null
                    || event.action() == null || event.summary() == null || event.sourceType() == null
                    || !Set.of("SUCCESS", "FAILURE", "PARTIAL", "NO_CHANGE", "UNKNOWN").contains(event.result())
                    || !Set.of("RESULT", "DATABASE_COMMIT", "EXTERNAL_RESULT", "ATTEMPT").contains(event.phase())
                    || event.durationMs() != null && event.durationMs() < 0) {
                throw new AuditPayloadException();
            }
            Set<String> objects = new HashSet<>();
            for (AuditChange change : event.objects()) {
                AuditObject object = change.object();
                if (!objects.add(object.objectType() + ":" + object.objectId()) || change.snapshotVersion() != 1
                        || !Set.of("CHANGE", "REFERENCE").contains(change.entryType())) {
                    throw new AuditPayloadException();
                }
                if ("REFERENCE".equals(change.entryType()) && (change.beforeData() != null || change.afterData() != null
                        || !change.changes().isEmpty() || !"NONE".equals(change.snapshotScope()))) {
                    throw new AuditPayloadException();
                }
            }
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw new AuditPayloadException();
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256不可用");
        }
    }

    private long insertSummary(AuditEvent event, Delivery delivery) {
        var row = new OperationLog();
        row.setEventId(event.eventId());
        row.setOperationId(event.operationId());
        row.setRequestId(event.requestId());
        row.setTraceId(event.traceId());
        row.setBatchId(event.batchId());
        row.setSchemaVersion(event.schemaVersion());
        row.setPhase(event.phase());
        row.setStartedAt(time(event.startedAt()));
        row.setOccurredAt(time(event.occurredAt()));
        row.setIndexedAt(time(now()));
        row.setDurationMs(event.durationMs());
        var actor = event.actor();
        row.setActorType(actor.type());
        row.setActorId(actor.id());
        row.setActorAccount(actor.account());
        row.setActorName(actor.name());
        row.setActorRoleCode(actor.roleCode());
        row.setActorRoleName(actor.roleName());
        row.setClaimedAccount(event.claimedAccount());
        row.setModuleCode(event.moduleCode());
        row.setOperationCode(event.operationCode());
        row.setAction(event.action());
        row.setSummary(event.summary());
        row.setObjectCount(event.objects().size());
        row.setChangedObjectCount(event.changedObjectCount());
        row.setFieldChangeCount(event.fieldChangeCount());
        row.setResult(event.result());
        row.setHttpStatus(event.httpStatus());
        row.setBusinessCode(event.businessCode());
        row.setErrorCode(event.errorCode());
        row.setErrorMessage(event.errorMessage());
        row.setSourceIp(event.sourceIp());
        row.setRequestMethod(event.requestMethod());
        row.setRequestPath(event.requestPath());
        row.setSourceType(event.sourceType());
        row.setPayloadHash(delivery.payloadHash());
        row.setPayloadBytes(delivery.payloadBytes());
        return requireGeneratedId(logs.insert(row), row.getId());
    }

    private void insertChanges(long logId, AuditEvent event) {
        if (event.objects().isEmpty()) {
            return;
        }
        var occurredAt = time(event.occurredAt());
        List<OperationChange> rows = new ArrayList<>(event.objects().size());
        for (var change : event.objects()) {
            var row = new OperationChange();
            row.setLogId(logId);
            row.setObjectNo(rows.size());
            row.setEntryType(change.entryType());
            var object = change.object();
            row.setObjectType(object.objectType());
            row.setObjectId(object.objectId());
            row.setObjectCode(object.objectCode());
            row.setObjectName(object.objectName());
            row.setParentType(object.parentType());
            row.setParentId(object.parentId());
            row.setOccurredAt(occurredAt);
            row.setSnapshotVersion(change.snapshotVersion());
            row.setSnapshotScope(change.snapshotScope());
            row.setBeforeData(change.beforeData() == null ? null : json.writeValueAsString(change.beforeData()));
            row.setAfterData(change.afterData() == null ? null : json.writeValueAsString(change.afterData()));
            row.setChanges(json.writeValueAsString(change.changes()));
            row.setFieldChangeCount(change.changes().size());
            rows.add(row);
        }
        // 使用当前数据源的会话工厂，确保批量明细和投递状态在同一事务中提交。
        batch.executeBatch(rows, rows.size(), OperationChangeMapper.class, OperationChangeMapper::insert);
    }

    private static long requireGeneratedId(int changed, Long id) {
        if (changed != 1 || id == null) {
            throw new IllegalStateException("审计事实未能持久化");
        }
        return id;
    }
}
