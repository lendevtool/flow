package me.lendev.flow.pms.workflownotification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.mail.MailOperationException;
import me.lendev.flow.mail.MailService;
import me.lendev.flow.mail.MailService.SubmissionReceipt;
import me.lendev.flow.pms.notificationgroup.WorkflowSceneCatalog;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeModels.InAppStatus;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeModels.MailStatus;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeProgress.Claim;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeProgress.LeaseLost;
import me.lendev.flow.pms.workflownotification.persistence.NoticeRecipient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 在流程事务提交后逐批写站内消息和提交独立邮件。
 */
@Component
@ConditionalOnProperty(name = "workflow.notification.delivery.enabled", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
class WorkflowNoticeWorker {

    private static final String MAIL_CALLER = "SYSTEM:WORKFLOW_NOTIFICATION";
    private static final String MAIL_RESULT_PENDING = "MAIL_RESULT_PENDING";

    private final WorkflowNoticeProgress progress;
    private final WorkflowSceneCatalog scenes;
    private final MailService mail;

    /**
     * 短事务领取后处理，单条投递的异常不阻塞其余投递的尝试。
     */
    @Scheduled(fixedDelayString = "${workflow.notification.delivery.poll-ms:5000}",
            initialDelayString = "${workflow.notification.delivery.poll-ms:5000}", scheduler = "workflowNoticeScheduler")
    public void poll() {
        for (long id : progress.eligible()) {
            try {
                deliver(id);
            } catch (RuntimeException failure) {
                log.warn("流程通知领取或结案失败，请检查投递队列和租约，后续轮询将处理到期记录，deliveryId={} errorType={}",
                        id, failure.getClass().getSimpleName());
            }
        }
    }

    void deliver(long id) {
        var claim = progress.claim(id);
        if (claim == null) {
            return;
        }
        String error;
        try {
            error = process(claim);
        } catch (LeaseLost staleWorker) {
            return;
        } catch (RuntimeException failure) {
            log.warn("流程通知处理异常，将结算本轮尝试，请检查场景配置、投递表及收件人记录，deliveryId={} eventId={} taskId={} sceneCode={} attemptCount={} errorType={}",
                    id, claim.delivery().getEventId(), claim.delivery().getTaskId(), claim.delivery().getSceneCode(),
                    claim.delivery().getAttemptCount(), failure.getClass().getSimpleName());
            error = "DELIVERY_PROCESSING_FAILED";
        }
        progress.finish(claim, error);
    }

    private String process(Claim claim) {
        if (!claim.reconcileOnly() && claim.delivery().getPreparedAt() == null && !prepare(claim)) {
            return "SCENE_CHANGED_DURING_PREPARATION";
        }
        String error = null;
        for (var recipient : progress.pendingRecipients(claim.id())) {
            try {
                String current = processRecipient(claim, recipient);
                if (error == null) {
                    error = current;
                }
            } catch (LeaseLost staleWorker) {
                throw staleWorker;
            } catch (RuntimeException failure) {
                error = "RECIPIENT_PROCESSING_FAILED";
                log.warn("流程通知收件人处理异常，请按投递 ID 和用户 ID 核对站内消息与邮件日志，deliveryId={} taskId={} sceneCode={} recipientUserId={} attemptCount={} errorCode={} errorType={}",
                        claim.id(), claim.delivery().getTaskId(), claim.delivery().getSceneCode(),
                        recipient.getRecipientUserId(), claim.delivery().getAttemptCount(), error,
                        failure.getClass().getSimpleName());
            }
        }
        return error;
    }

    private boolean prepare(Claim claim) {
        for (int i = 0; i < 3; i++) {
            var snapshot = scenes.prepare(claim.delivery().getSceneCode());
            if (progress.freeze(claim, snapshot)) {
                return true;
            }
        }
        return false;
    }

    private String processRecipient(Claim claim, NoticeRecipient recipient) {
        int userId = recipient.getRecipientUserId();
        boolean mailPending = recipient.getInAppStatus() == InAppStatus.WRITTEN && recipient.getMailStatus() == MailStatus.PENDING;
        if (recipient.getInAppStatus() == InAppStatus.PENDING && !claim.reconcileOnly()) {
            mailPending = progress.publish(claim, userId);
        }
        return mailPending ? processMail(claim, recipient) : null;
    }

    /**
     * 先按原键对账。无日志时才尝试读取或保存提交参数，提交邮件期间没有打开的通知事务。
     */
    private String processMail(Claim claim, NoticeRecipient recipient) {
        int userId = recipient.getRecipientUserId();
        String key = "wf-notify:" + claim.id() + ":" + userId;
        var existing = mail.findSubmission(MAIL_CALLER, key);
        if (existing.isPresent()) {
            return reconcile(claim, userId, existing.get());
        }
        if (claim.reconcileOnly() && recipient.getMailRequestJson() != null) {
            progress.mailError(claim, userId, "MAIL_NOT_SUBMITTED", true);
            return null;
        }
        var request = progress.mailRequest(claim, userId);
        if (request == null) {
            return null;
        }
        String requestId = "wf-notify:" + claim.id();
        try {
            var result = mail.sendByTemplateId(request.templateId(), request.recipient(), request.variables(), MAIL_CALLER, key,
                    requestId);
            var receipt = mail.findSubmission(MAIL_CALLER, key)
                    .orElseGet(() -> new SubmissionReceipt(result.logId(), result.status(), null));
            return reconcile(claim, userId, receipt);
        } catch (MailOperationException failure) {
            return mailFailure(claim, userId, key, failure.code(), !failure.status().is5xxServerError());
        } catch (BadRequestException failure) {
            return mailFailure(claim, userId, key, "MAIL_TEMPLATE_RENDER_FAILED", true);
        } catch (LeaseLost staleWorker) {
            throw staleWorker;
        } catch (RuntimeException failure) {
            return mailFailure(claim, userId, key, "MAIL_SUBMISSION_FAILED", false);
        }
    }

    private String mailFailure(Claim claim, int userId, String key, String code, boolean terminal) {
        var existing = mail.findSubmission(MAIL_CALLER, key);
        if (existing.isPresent()) {
            return reconcile(claim, userId, existing.get());
        }
        boolean finished = terminal || claim.lastAttempt();
        progress.mailError(claim, userId, code, finished);
        return finished ? null : code;
    }

    private String reconcile(Claim claim, int userId, SubmissionReceipt receipt) {
        return progress.reconcile(claim, userId, receipt) ? MAIL_RESULT_PENDING : null;
    }
}
