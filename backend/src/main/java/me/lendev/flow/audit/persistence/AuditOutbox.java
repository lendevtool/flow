package me.lendev.flow.audit.persistence;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import lombok.ToString;

/**
 * 审计队列（outbox）记录及其投递租约，对应表 {@code sys_audit_outbox}。
 *
 * <p>状态流转：PREPARED（外部意图待确认）→ PENDING（待投递）→ PROCESSING（已领取）
 * → DONE（投递完成）；失败进入 RETRY 并按退避延后，永久失败或达到上限进入 DEAD。
 * workerId/leaseToken/lockedUntil 构成投递租约，rowVersion 用于乐观并发控制。
 * payload 不参与 toString，防止敏感载荷进入日志。
 */
@Data
@Table(value = "sys_audit_outbox", camelToUnderline = false)
public class AuditOutbox {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String eventId;
    private String operationId;
    private String requestId;
    private String traceId;
    private String batchId;
    private Integer schemaVersion;
    private LocalDateTime occurredAt;
    private String moduleCode;
    private String operationCode;
    private String phase;
    private String result;
    @ToString.Exclude
    private String payload;
    private Long payloadBytes;
    private String payloadHash;
    private String status;
    private Integer attempts;
    private Integer failureCount;
    private Integer manualRetryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime prepareDeadlineAt;
    private String workerId;
    private String leaseToken;
    private LocalDateTime lockedUntil;
    private Long rowVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime processedAt;
    private String lastErrorCode;
    private String lastErrorMessage;
    private String lastRetriedBy;
    private LocalDateTime lastRetriedAt;
}
