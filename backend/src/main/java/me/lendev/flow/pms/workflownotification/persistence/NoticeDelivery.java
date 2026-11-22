package me.lendev.flow.pms.workflownotification.persistence;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeModels.Kind;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeModels.Status;

/**
 * 一次节点附加场景或可选待办邮件的投递请求、固定方案及处理租约。
 */
@Getter
@Setter
@Table("wf_notification_delivery")
public class NoticeDelivery {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String triggerKey;
    private long flowInstanceId;
    private long nodeInstanceId;
    private Long eventId;
    private Long taskId;
    private Kind kind;
    private String sceneCode;
    private String eventContextJson;
    private Long sceneVersion;
    private LocalDateTime preparedAt;
    private String planJson;
    private Status status;
    private int attemptCount;
    private LocalDateTime nextAttemptAt;
    private String leaseToken;
    private LocalDateTime leaseExpiresAt;
    private String lastErrorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime finishedAt;
}
