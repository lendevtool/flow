package me.lendev.flow.workbench.flow.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;

/**
 * 业务结果投递的持久化实体，对应 wf_business_delivery 表，保存投递状态、重试次数、下次重试时间及处理租约。
 */
@Getter
@Setter
@Table(value = "wf_business_delivery", camelToUnderline = false)
public class WorkflowDelivery {

    @Id
    private Long eventId;
    /**
     * 投递状态：待投递、已投递、失败待重试等。
     */
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    /**
     * 处理租约令牌，防止多消费者并发处理同一事件。
     */
    private String leaseToken;
    private LocalDateTime leaseExpiresAt;
    /**
     * 投递载荷 JSON 快照。
     */
    private String payloadJson;
    private LocalDateTime deliveredAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
