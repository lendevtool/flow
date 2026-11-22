package me.lendev.flow.pms.workflownotification;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import me.lendev.flow.mail.MailModels.Recipient;

/**
 * 流程通知队列和管理端共用的状态及数据契约。
 */
public final class WorkflowNoticeModels {

    private WorkflowNoticeModels() {
    }

    public enum Status {PENDING, PROCESSING, RETRY, DONE, PARTIAL, SKIPPED, FAILED}

    public enum Kind {
        NODE_APPROVED, NODE_REJECTED, TASK_ASSIGNED_MAIL, TASK_RETURNED_MAIL;

        boolean taskMail() {
            return this == TASK_ASSIGNED_MAIL || this == TASK_RETURNED_MAIL;
        }
    }

    public enum InAppStatus {PENDING, WRITTEN, SKIPPED, FAILED}

    public enum MailStatus {OFF, PENDING, ACCEPTED, SKIPPED, FAILED, UNKNOWN}

    record EventContext(long activationVersion, Map<String, String> variables) {
    }

    record Plan(String title, String summary, Long templateId, Map<String, String> variables) {
    }

    record MailRequest(long templateId, Recipient recipient, Map<String, String> variables) {
    }

    /**
     * 管理端列表筛选；起始时间含边界，结束时间不含边界。
     */
    public record Query(@Positive Integer pageNo, @Positive @Max(100) Integer pageSize,
                        @Positive Long flowInstanceId, @Pattern(regexp = "[a-z][a-z0-9_.]{0,63}") String sceneCode,
                        Status status, Instant from, Instant to) {
    }

    /**
     * 供管理员查询的状态摘要。
     */
    public record DeliveryRow(long id, long flowInstanceId, long nodeInstanceId, Long eventId, Long taskId,
                              String sceneCode, Kind kind, Status status, int attemptCount, Long sceneVersion,
                              Instant createdAt, Instant preparedAt, Instant finishedAt, String lastErrorCode) {
    }

    /**
     * 渠道处理结果及已产生的历史记录关联。
     */
    public record RecipientRow(int recipientUserId, InAppStatus inAppStatus, Long messageId, MailStatus mailStatus,
                               Long mailLogId, String inAppErrorCode, String mailErrorCode) {
    }

    /**
     * 不含消息正文、邮箱或邮件提交参数的投递明细。
     */
    public record Detail(DeliveryRow delivery, List<RecipientRow> recipients) {
    }
}
