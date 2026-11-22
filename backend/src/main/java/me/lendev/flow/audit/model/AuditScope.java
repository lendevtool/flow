package me.lendev.flow.audit.model;

import java.time.Instant;
import java.util.List;

import lombok.Builder;

/**
 * 审计调用作用域：仅保存安全的调用元数据，原始方法参数绝不超出执行期留存。
 */
@Builder(toBuilder = true)
public record AuditScope(String operationId, String requestId, String traceId,
                         Instant startedAt, long startedNanos, AuditActor actor,
                         String claimedAccount, String sourceIp, String requestMethod,
                         String requestPath, String sourceType, List<AuditObject> targets) {

    public AuditScope {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
