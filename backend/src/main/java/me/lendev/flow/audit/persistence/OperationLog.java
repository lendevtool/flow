package me.lendev.flow.audit.persistence;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

/**
 * 已投递审计事件的摘要记录，对应表 {@code sys_operation_log}。
 *
 * <p>由 outbox 消费者幂等写入，eventId 唯一；payloadHash/payloadBytes 用于校验
 * 重复投递的载荷一致性。对象级明细见 {@link OperationChange}。
 */
@Data
@Table(value = "sys_operation_log", camelToUnderline = false)
public class OperationLog {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String eventId;
    private String operationId;
    private String requestId;
    private String traceId;
    private String batchId;
    private Integer schemaVersion;
    private String phase;
    private LocalDateTime startedAt;
    private LocalDateTime occurredAt;
    private LocalDateTime indexedAt;
    private Long durationMs;
    private String actorType;
    private String actorId;
    private String actorAccount;
    private String actorName;
    private String actorRoleCode;
    private String actorRoleName;
    private String claimedAccount;
    private String moduleCode;
    private String operationCode;
    private String action;
    private String summary;
    private Integer objectCount;
    private Integer changedObjectCount;
    private Integer fieldChangeCount;
    private String result;
    private Integer httpStatus;
    private String businessCode;
    private String errorCode;
    private String errorMessage;
    private String sourceIp;
    private String requestMethod;
    private String requestPath;
    private String sourceType;
    private String payloadHash;
    private Long payloadBytes;
}
