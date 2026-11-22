package me.lendev.flow.pms.workflownotification.persistence;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeModels.InAppStatus;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeModels.MailStatus;

/**
 * 固定名单中的收件人及其独立渠道进度。
 */
@Getter
@Setter
@Table("wf_notification_recipient")
public class NoticeRecipient {

    @Id
    private long deliveryId;
    @Id
    private int recipientUserId;
    private InAppStatus inAppStatus;
    private Long messageId;
    private MailStatus mailStatus;
    private Long mailLogId;
    private String mailRequestJson;
    private String inAppErrorCode;
    private String mailErrorCode;
    private LocalDateTime updatedAt;
}
