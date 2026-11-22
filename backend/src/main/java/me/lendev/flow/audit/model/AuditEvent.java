package me.lendev.flow.audit.model;

import java.time.Instant;
import java.util.List;

import lombok.Builder;

/**
 * 冻结的审计事实事件；哈希与字节数均基于其精确序列化后的 UTF-8 载荷计算。
 */
@Builder(toBuilder = true)
public record AuditEvent(String eventId, String operationId, String requestId, String traceId,
                         String batchId, int schemaVersion, String phase, Instant startedAt,
                         Instant occurredAt, Long durationMs, AuditActor actor, String claimedAccount,
                         String moduleCode, String operationCode, String action, String summary,
                         String result, Integer httpStatus, String businessCode, String errorCode,
                         String errorMessage, String sourceIp, String requestMethod,
                         String requestPath, String sourceType, List<AuditChange> objects) {

    public AuditEvent {
        objects = objects == null ? List.of() : List.copyOf(objects);
    }

    /**
     * @return 发生实际字段变更的对象数量（不含仅引用对象）
     */
    public int changedObjectCount() {
        return (int) objects.stream().filter(AuditChange::changed).count();
    }

    /**
     * @return 全部对象的字段级变更总数
     */
    public int fieldChangeCount() {
        return objects.stream().mapToInt(change -> change.changes().size()).sum();
    }
}
