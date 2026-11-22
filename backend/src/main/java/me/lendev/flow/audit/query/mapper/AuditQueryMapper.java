package me.lendev.flow.audit.query.mapper;

import static com.mybatisflex.core.query.QueryMethods.count;
import static com.mybatisflex.core.query.QueryMethods.exists;
import static com.mybatisflex.core.query.QueryMethods.min;
import static com.mybatisflex.core.query.QueryMethods.raw;
import static me.lendev.flow.audit.persistence.table.AuditOutboxTableDef.AUDIT_OUTBOX;
import static me.lendev.flow.audit.persistence.table.OperationChangeTableDef.OPERATION_CHANGE;
import static me.lendev.flow.audit.persistence.table.OperationLogTableDef.OPERATION_LOG;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryTable;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.Data;
import me.lendev.flow.audit.persistence.AuditOutbox;
import me.lendev.flow.audit.persistence.OperationChange;
import me.lendev.flow.audit.persistence.OperationLog;
import org.apache.ibatis.annotations.Select;

/**
 * 查询审计日志、变更明细和队列状态，响应列由固定的列清单限定。
 */
public interface AuditQueryMapper extends BaseMapper<OperationLog> {

    /**
     * 审计日志摘要的响应列白名单，逗号分隔。
     */
    String LOG = "id,eventId,operationId,requestId,phase,startedAt,occurredAt,indexedAt,durationMs,actorType,actorId,"
            + "actorAccount,actorName,actorRoleCode,actorRoleName,claimedAccount,moduleCode,operationCode,action,summary,"
            + "objectCount,changedObjectCount,fieldChangeCount,result,httpStatus,businessCode,errorCode,errorMessage,"
            + "sourceIp,requestMethod,requestPath,sourceType";
    /**
     * 对象变更明细的响应列白名单，逗号分隔。
     */
    String CHANGE = "id,logId,objectNo,entryType,objectType,objectId,objectCode,objectName,parentType,parentId,"
            + "occurredAt,snapshotVersion,snapshotScope,beforeData,afterData,changes,fieldChangeCount";
    /**
     * outbox 队列状态的响应列白名单，逗号分隔；不含 payload。
     */
    String QUEUE = "id,eventId,operationId,moduleCode,operationCode,phase,result,status,attempts,failureCount,"
            + "manualRetryCount,nextRetryAt,prepareDeadlineAt,lockedUntil,rowVersion,createdAt,updatedAt,lastErrorCode,"
            + "lastErrorMessage,lastRetriedBy,lastRetriedAt,payloadBytes";

    /**
     * 统计满足筛选条件的审计日志总数。
     */
    default long countLogs(Map<String, Object> filter) {
        return selectCountByQuery(logFilter(filter));
    }

    /**
     * 分页查询审计日志摘要，按发生时间倒序。
     */
    default List<OperationLog> logs(Map<String, Object> filter, long offset, int limit) {
        return selectListByQuery(logFilter(filter).select(Projection.LOG_COLUMNS)
                .orderBy(OPERATION_LOG.OCCURRED_AT.desc(), OPERATION_LOG.ID.desc()).limit(offset, limit));
    }

    /**
     * 按主键读取单条审计日志摘要。
     *
     * @return 日志摘要；不存在时返回 {@code null}
     */
    default OperationLog findLog(long id) {
        return selectOneByQuery(QueryWrapper.create().select(Projection.LOG_COLUMNS).where(OPERATION_LOG.ID.eq(id)));
    }

    /**
     * 读取已投递事件的载荷哈希，用于校验重复投递。
     */
    default String loggedPayloadHash(String eventId) {
        return selectObjectByQueryAs(QueryWrapper.create().select(OPERATION_LOG.PAYLOAD_HASH)
                .where(OPERATION_LOG.EVENT_ID.eq(eventId)), String.class);
    }

    /**
     * 统计指定日志的对象变更明细总数。
     */
    default long countChanges(long id) {
        return selectCountByQuery(changesFor(id));
    }

    /**
     * 分页查询指定日志的对象变更明细，按对象序号升序。
     */
    default List<OperationChange> changes(long id, long offset, int limit) {
        return selectListByQueryAs(changesFor(id).select(Projection.CHANGE_COLUMNS)
                .orderBy(OPERATION_CHANGE.OBJECT_NO.asc()).limit(offset, limit), OperationChange.class);
    }

    /**
     * 统计队列记录数；status 为 {@code null} 时仅统计 RETRY/DEAD。
     */
    default long countQueue(String status) {
        return selectCountByQuery(queueFilter(status));
    }

    /**
     * 分页查询队列记录，按创建时间倒序；status 为 {@code null} 时仅返回 RETRY/DEAD。
     */
    default List<AuditOutbox> pageQueue(String status, long offset, int limit) {
        return selectListByQueryAs(queueFilter(status).select(Projection.QUEUE_COLUMNS)
                .orderBy(AUDIT_OUTBOX.CREATED_AT.desc(), AUDIT_OUTBOX.ID.desc()).limit(offset, limit), AuditOutbox.class);
    }

    /**
     * 按状态分组统计未完成任务（不含 DONE）的队列积压数量。
     */
    default List<QueueCount> queueCounts() {
        return selectListByQueryAs(QueryWrapper.create().select(AUDIT_OUTBOX.STATUS, count().as("amount"))
                .from(AUDIT_OUTBOX).where(AUDIT_OUTBOX.STATUS.ne("DONE")).groupBy(AUDIT_OUTBOX.STATUS), QueueCount.class);
    }

    /**
     * 读取最早一条未完成任务的创建时间，用于衡量积压时长。
     *
     * @return 最早创建时间；无积压时返回 {@code null}
     */
    default LocalDateTime oldestPendingAt() {
        var row = selectOneByQueryAs(QueryWrapper.create().select(min(AUDIT_OUTBOX.CREATED_AT).as("createdAt"))
                        .from(AUDIT_OUTBOX).where(AUDIT_OUTBOX.STATUS.in("PREPARED", "PENDING", "PROCESSING", "RETRY")),
                AuditOutbox.class);
        return row == null ? null : row.getCreatedAt();
    }

    /**
     * 启动时用一次查询确认三张表都可访问。
     */
    @Select("""
            SELECT id FROM sys_audit_outbox WHERE 1 = 0
            UNION ALL SELECT id FROM sys_operation_log WHERE 1 = 0
            UNION ALL SELECT id FROM sys_operation_change WHERE 1 = 0
            """)
    List<Long> probeTables();

    /**
     * 锁住 outbox 行，保证人工重试的前后快照处于同一事务。
     */
    default AuditOutbox lockQueueSnapshot(long id) {
        return selectOneByQueryAs(QueryWrapper.create().select(AUDIT_OUTBOX.EVENT_ID, AUDIT_OUTBOX.OPERATION_CODE,
                        AUDIT_OUTBOX.STATUS, AUDIT_OUTBOX.ATTEMPTS, AUDIT_OUTBOX.FAILURE_COUNT, AUDIT_OUTBOX.MANUAL_RETRY_COUNT)
                .from(AUDIT_OUTBOX).where(AUDIT_OUTBOX.ID.eq(id)).forUpdate(), AuditOutbox.class);
    }

    private static QueryWrapper logFilter(Map<String, Object> filter) {
        var query = QueryWrapper.create().from(OPERATION_LOG)
                .where(OPERATION_LOG.OCCURRED_AT.ge(filter.get("startTime")).and(OPERATION_LOG.OCCURRED_AT.le(filter.get("endTime"))))
                .and(OPERATION_LOG.ACTOR_ID.eq(filter.get("actorId")))
                .and(OPERATION_LOG.MODULE_CODE.eq(filter.get("moduleCode")))
                .and(OPERATION_LOG.OPERATION_CODE.eq(filter.get("operationCode")))
                .and(OPERATION_LOG.RESULT.eq(filter.get("result")))
                .and(OPERATION_LOG.REQUEST_ID.eq(filter.get("requestId")))
                .and(OPERATION_LOG.OPERATION_ID.eq(filter.get("operationId")));
        Object keyword = filter.get("actorKeyword");
        if (keyword != null) {
            query.and(raw("(actorName LIKE ? ESCAPE '!' OR actorAccount LIKE ? ESCAPE '!' OR claimedAccount LIKE ? ESCAPE '!')",
                    keyword, keyword, keyword));
        }
        if (Integer.valueOf(1).equals(filter.get("filterObjects"))) {
            query.and(exists(QueryWrapper.create().select(OPERATION_CHANGE.ID).from(OPERATION_CHANGE)
                    .where(OPERATION_CHANGE.LOG_ID.eq(OPERATION_LOG.ID))
                    .and(OPERATION_CHANGE.OBJECT_TYPE.eq(filter.get("objectType")))
                    .and(OPERATION_CHANGE.OBJECT_ID.eq(filter.get("objectId")))
                    .and(OPERATION_CHANGE.OBJECT_CODE.eq(filter.get("objectCode")))
                    .and(OPERATION_CHANGE.PARENT_TYPE.eq(filter.get("parentType")))
                    .and(OPERATION_CHANGE.PARENT_ID.eq(filter.get("parentId")))));
        }
        return query;
    }

    private static QueryWrapper changesFor(long id) {
        return QueryWrapper.create().from(OPERATION_CHANGE).where(OPERATION_CHANGE.LOG_ID.eq(id));
    }

    private static QueryWrapper queueFilter(String status) {
        return QueryWrapper.create().from(AUDIT_OUTBOX)
                .where(status == null ? AUDIT_OUTBOX.STATUS.in("RETRY", "DEAD") : AUDIT_OUTBOX.STATUS.eq(status));
    }

    /**
     * 固定列白名单投影：将逗号分隔的列名清单解析为查询列，限定响应字段范围。
     */
    final class Projection {

        private static final QueryColumn[] LOG_COLUMNS = columns(OPERATION_LOG, LOG);
        private static final QueryColumn[] CHANGE_COLUMNS = columns(OPERATION_CHANGE, CHANGE);
        private static final QueryColumn[] QUEUE_COLUMNS = columns(AUDIT_OUTBOX, QUEUE);

        private Projection() {
        }

        private static QueryColumn[] columns(QueryTable table, String whitelist) {
            return Arrays.stream(whitelist.split(",")).map(name -> new QueryColumn(table, name))
                    .toArray(QueryColumn[]::new);
        }
    }

    /**
     * 队列按状态分组的计数结果。
     */
    @Data
    class QueueCount {

        private String status;
        private long amount;
    }
}
