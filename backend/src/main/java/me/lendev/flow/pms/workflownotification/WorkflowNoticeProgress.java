package me.lendev.flow.pms.workflownotification;

import static me.lendev.flow.pms.workflownotification.WorkflowNoticeModels.*;
import static me.lendev.flow.pms.workflownotification.persistence.table.NoticeDeliveryTableDef.NOTICE_DELIVERY;
import static me.lendev.flow.pms.workflownotification.persistence.table.NoticeRecipientTableDef.NOTICE_RECIPIENT;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.mybatisflex.core.query.QueryWrapper;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.mail.MailModels.Recipient;
import me.lendev.flow.mail.MailService.SubmissionReceipt;
import me.lendev.flow.pms.inapp.InAppMessageService;
import me.lendev.flow.pms.inapp.InAppMessageService.Write;
import me.lendev.flow.pms.notificationgroup.NotificationRecipientResolver;
import me.lendev.flow.pms.notificationgroup.WorkflowSceneCatalog;
import me.lendev.flow.pms.notificationgroup.WorkflowSceneCatalog.PreparedScene;
import me.lendev.flow.pms.workflownotification.mapper.NoticeDeliveryMapper;
import me.lendev.flow.pms.workflownotification.mapper.NoticeRecipientMapper;
import me.lendev.flow.pms.workflownotification.persistence.NoticeDelivery;
import me.lendev.flow.pms.workflownotification.persistence.NoticeRecipient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 执行带租约校验的短事务，固定整批方案并保存每个渠道的进度。
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(propagation = Propagation.REQUIRES_NEW)
class WorkflowNoticeProgress {

    private static final int MAX_ATTEMPTS = 5;
    private static final int LEASE_SECONDS = 120;
    private static final String RECIPIENT_DISABLED = "RECIPIENT_DISABLED";
    private static final String MAIL_RESULT_PENDING = "MAIL_RESULT_PENDING";
    private static final String RETRY_EXHAUSTED = "RETRY_EXHAUSTED";
    private static final String SCENE_INVALID = "SCENE_CONFIGURATION_INVALID";

    private final NoticeDeliveryMapper deliveries;
    private final NoticeRecipientMapper recipients;
    private final WorkflowSceneCatalog scenes;
    private final InAppMessageService inbox;
    private final ObjectMapper json;
    private final Clock clock;
    private final Validator validator;

    /**
     * 领取后的只读请求快照和本轮租约。
     */
    record Claim(NoticeDelivery delivery, String token, boolean reconcileOnly) {
        long id() {
            return delivery.getId();
        }

        boolean lastAttempt() {
            return delivery.getAttemptCount() >= MAX_ATTEMPTS;
        }
    }

    @Transactional(readOnly = true)
    public List<Long> eligible() {
        return deliveries.eligible(now(), 20);
    }

    /**
     * 领取唯一租约；已用完尝试次数的过期记录仅允许对账及结案。
     */
    public Claim claim(long id) {
        var row = lock(id);
        if (row == null) {
            return null;
        }
        var now = now();
        boolean reconcileOnly = row.getAttemptCount() >= MAX_ATTEMPTS;
        int count = reconcileOnly ? row.getAttemptCount() : row.getAttemptCount() + 1;
        String token = UUID.randomUUID().toString();
        if (deliveries.claimIfDue(id, now, now.plusSeconds(LEASE_SECONDS), token, count) == 0) {
            return null;
        }
        row.setAttemptCount(count);
        log.info("流程通知领取成功，deliveryId={} eventId={} taskId={} sceneCode={} attemptCount={}",
                id, row.getEventId(), row.getTaskId(), row.getSceneCode(), count);
        return new Claim(row, token, reconcileOnly);
    }

    /**
     * 锁定主行再锁定场景。配置换版时返回 false 以重新读取快照，配置和名单一起固定。
     * 活跃周期失效、空名单或配置异常时在同一事务结束投递。
     */
    public boolean freeze(Claim claim, PreparedScene snapshot) {
        long started = System.nanoTime();
        var row = owned(claim);
        if (row.getPreparedAt() != null) {
            return true;
        }
        var current = scenes.lock(row.getSceneCode());
        var context = json.readValue(row.getEventContextJson(), EventContext.class);
        if (current == null || !WorkflowSceneCatalog.EXTRA.equals(current.type())) {
            return terminatePreparation(row, Status.FAILED, SCENE_INVALID);
        }
        if (!current.enabled()) {
            return terminatePreparation(row, Status.SKIPPED, "SCENE_DISABLED");
        }
        if (context.activationVersion() != current.activationVersion()) {
            return terminatePreparation(row, Status.SKIPPED, "SCENE_ACTIVATION_CHANGED");
        }
        if (snapshot == null || snapshot.scene() == null || current.version() != snapshot.scene().version()) {
            return false;
        }

        var resolved = snapshot.recipients();
        if (resolved == null || !Objects.equals(resolved.sceneVersion(), current.version())) {
            return terminatePreparation(row, Status.FAILED, SCENE_INVALID);
        }
        if (resolved.status() != NotificationRecipientResolver.Status.READY) {
            return switch (resolved.status()) {
                case NO_GROUPS, NO_RECIPIENTS -> terminatePreparation(row, Status.SKIPPED, resolved.status().name());
                default -> terminatePreparation(row, Status.FAILED, resolved.status().name());
            };
        }
        var variables = context.variables();
        String title = rendered(current.titleTemplate(), variables, "流程节点状态已更新", 200);
        String summary = rendered(current.summaryTemplate(), variables, "流程节点状态已更新。", 1000);
        var plan = new Plan(title, summary, current.mailTemplateId(), variables);
        var now = now();
        WorkflowNoticeQueue.freeze(row, current.version(), plan, now, json);
        deliveries.update(row, false);
        var entries = resolved.userIds().stream().map(user -> WorkflowNoticeQueue.recipient(
                row.getId(), Math.toIntExact(user), null, plan.templateId() != null, now)).toList();
        recipients.insertBatch(entries, 200);
        log.info("流程通知方案固定，deliveryId={} eventId={} sceneCode={} sceneVersion={} recipientCount={} elapsedMs={}",
                row.getId(), row.getEventId(), row.getSceneCode(), current.version(), entries.size(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return true;
    }

    @Transactional(readOnly = true)
    public List<NoticeRecipient> pendingRecipients(long id) {
        return recipients.selectListByQuery(forDelivery(id)
                .and(NOTICE_RECIPIENT.IN_APP_STATUS.eq(InAppStatus.PENDING)
                        .or(NOTICE_RECIPIENT.MAIL_STATUS.eq(MailStatus.PENDING)))
                .orderBy(NOTICE_RECIPIENT.RECIPIENT_USER_ID.asc()));
    }

    /**
     * 将个人消息和进度置为已写入，或在账号停用时记录渠道跳过。
     */
    public boolean publish(Claim claim, int userId) {
        var row = owned(claim);
        var recipient = recipient(row.getId(), userId);
        if (recipient.getInAppStatus() == InAppStatus.PENDING) {
            if (recipients.activeUser(userId) == null) {
                recipient.setInAppStatus(InAppStatus.SKIPPED);
                recipient.setInAppErrorCode(RECIPIENT_DISABLED);
                if (recipient.getMailStatus() == MailStatus.PENDING) {
                    recipient.setMailStatus(MailStatus.SKIPPED);
                    recipient.setMailErrorCode(RECIPIENT_DISABLED);
                }
            } else {
                var plan = json.readValue(row.getPlanJson(), Plan.class);
                long message = inbox.publish(new Write(userId, "WORKFLOW", row.getTriggerKey(), row.getKind().name(),
                        plan.title(), plan.summary(), "WORKFLOW_INSTANCE", row.getFlowInstanceId()));
                recipient.setMessageId(message);
                recipient.setInAppStatus(InAppStatus.WRITTEN);
            }
            updateRecipient(recipient);
        }
        return recipient.getInAppStatus() == InAppStatus.WRITTEN && recipient.getMailStatus() == MailStatus.PENDING;
    }

    /**
     * 在确认无既有发送日志后执行。首次保存稳定参数，后续调用沿用；已关联日志时由对账恢复。
     */
    public MailRequest mailRequest(Claim claim, int userId) {
        var row = owned(claim);
        var recipient = recipient(row.getId(), userId);
        if (claim.reconcileOnly() || recipient.getMailStatus() != MailStatus.PENDING
                || recipient.getInAppStatus() != InAppStatus.WRITTEN || recipient.getMailLogId() != null) {
            return null;
        }
        if (recipient.getMailRequestJson() != null) {
            return json.readValue(recipient.getMailRequestJson(), MailRequest.class);
        }
        var user = recipients.activeUser(userId);
        if (user == null) {
            skipMail(recipient, RECIPIENT_DISABLED);
            return null;
        }
        if (row.getKind().taskMail() && deliveries.openTask(row.getTaskId(), userId) == 0) {
            skipMail(recipient, "TASK_CLOSED");
            return null;
        }
        String address = user.email() == null ? null : user.email().strip();
        if (address == null || !validator.validateValue(Recipient.class, "address", address).isEmpty()) {
            skipMail(recipient, "MAIL_ADDRESS_UNAVAILABLE");
            return null;
        }
        var plan = json.readValue(row.getPlanJson(), Plan.class);
        var request = new MailRequest(plan.templateId(), new Recipient(address, user.name()), plan.variables());
        recipient.setMailRequestJson(json.writeValueAsString(request));
        updateRecipient(recipient);
        return request;
    }

    /**
     * 对账时记录实际日志 ID 与渠道状态，多个尝试只引用同一条邮件日志。
     */
    public boolean reconcile(Claim claim, int userId, SubmissionReceipt receipt) {
        var row = owned(claim);
        var recipient = recipient(row.getId(), userId);
        if (recipient.getMailStatus() != MailStatus.PENDING) {
            return false;
        }
        recipient.setMailLogId(receipt.logId());
        recipient.setMailRequestJson(null);
        recipient.setMailStatus(switch (receipt.status()) {
            case PENDING, PROCESSING -> MailStatus.PENDING;
            case ACCEPTED -> MailStatus.ACCEPTED;
            case FAILED -> MailStatus.FAILED;
            case UNKNOWN, PARTIAL -> MailStatus.UNKNOWN;
        });
        recipient.setMailErrorCode(switch (receipt.status()) {
            case ACCEPTED -> null;
            case PENDING, PROCESSING -> MAIL_RESULT_PENDING;
            case PARTIAL -> "MAIL_PARTIAL_RESULT";
            default -> Objects.requireNonNullElse(receipt.errorCode(), "MAIL_" + receipt.status().name());
        });
        updateRecipient(recipient);
        return recipient.getMailStatus() == MailStatus.PENDING;
    }

    /**
     * 没有既有发送日志时，记录配置拒绝或需要重试的渠道错误。
     */
    public void mailError(Claim claim, int userId, String code, boolean terminal) {
        var row = owned(claim);
        var recipient = recipient(row.getId(), userId);
        if (recipient.getMailStatus() == MailStatus.PENDING && recipient.getMailLogId() == null) {
            if (terminal) {
                recipient.setMailStatus(MailStatus.FAILED);
                recipient.setMailRequestJson(null);
            }
            recipient.setMailErrorCode(code);
            updateRecipient(recipient);
        }
    }

    /**
     * 汇总各已安排的渠道，并在仍有待处理项时安排退避或耗尽结案。
     */
    public boolean finish(Claim claim, String error) {
        var row = lock(claim.id());
        var now = now();
        if (!owns(row, claim, now)) {
            return false;
        }
        var entries = recipients.selectListByQuery(forDelivery(row.getId()));
        boolean pending = row.getPreparedAt() == null || entries.stream().anyMatch(WorkflowNoticeProgress::pending);
        if (pending && row.getAttemptCount() < MAX_ATTEMPTS) {
            row.setStatus(Status.RETRY);
            row.setLeaseToken(null);
            row.setLeaseExpiresAt(null);
            row.setLastErrorCode(Objects.requireNonNullElse(error, MAIL_RESULT_PENDING));
            row.setNextAttemptAt(now.plusMinutes(1L << (row.getAttemptCount() - 1)));
            row.setUpdatedAt(now);
        } else {
            if (pending) {
                entries.stream().filter(WorkflowNoticeProgress::pending).forEach(this::exhaust);
            }
            var aggregate = aggregate(row, entries);
            String reason = entries.stream().map(WorkflowNoticeProgress::reason).filter(Objects::nonNull).findFirst()
                    .orElse(pending ? Objects.requireNonNullElse(error, RETRY_EXHAUSTED) : error);
            WorkflowNoticeQueue.terminate(row, aggregate, aggregate == Status.DONE ? null : reason, now);
        }
        deliveries.update(row, false);
        if (row.getStatus() == Status.FAILED || row.getStatus() == Status.PARTIAL && entries.stream().anyMatch(
                entry -> entry.getInAppStatus() == InAppStatus.FAILED
                        || entry.getMailStatus() == MailStatus.FAILED || entry.getMailStatus() == MailStatus.UNKNOWN)) {
            log.warn("流程通知结案存在未完成渠道，请按投递 ID 检查各收件人渠道状态，邮件结果未知时先核对邮件提交记录，deliveryId={} instanceId={} eventId={} taskId={} sceneCode={} sceneVersion={} status={} attemptCount={} recipientCount={} errorCode={}",
                    row.getId(), row.getFlowInstanceId(), row.getEventId(), row.getTaskId(), row.getSceneCode(),
                    row.getSceneVersion(), row.getStatus(), row.getAttemptCount(), entries.size(), row.getLastErrorCode());
            return true;
        }
        log.info("流程通知尝试结束，deliveryId={} eventId={} taskId={} sceneCode={} sceneVersion={} status={} recipientCount={} errorCode={}",
                row.getId(), row.getEventId(), row.getTaskId(), row.getSceneCode(), row.getSceneVersion(),
                row.getStatus(), entries.size(), row.getLastErrorCode());
        return true;
    }

    private void exhaust(NoticeRecipient recipient) {
        if (recipient.getInAppStatus() == InAppStatus.PENDING) {
            recipient.setInAppStatus(InAppStatus.FAILED);
            recipient.setInAppErrorCode(RETRY_EXHAUSTED);
        }
        if (recipient.getMailStatus() == MailStatus.PENDING) {
            boolean unconfirmed = recipient.getMailLogId() != null || recipient.getMailRequestJson() != null;
            recipient.setMailStatus(unconfirmed ? MailStatus.UNKNOWN : MailStatus.FAILED);
            recipient.setMailErrorCode(unconfirmed ? "MAIL_RESULT_UNCONFIRMED"
                    : Objects.requireNonNullElse(recipient.getMailErrorCode(), RETRY_EXHAUSTED));
            recipient.setMailRequestJson(null);
        }
        updateRecipient(recipient);
    }

    private static boolean pending(NoticeRecipient recipient) {
        return recipient.getInAppStatus() == InAppStatus.PENDING || recipient.getMailStatus() == MailStatus.PENDING;
    }

    private static String reason(NoticeRecipient recipient) {
        return recipient.getInAppErrorCode() == null ? recipient.getMailErrorCode() : recipient.getInAppErrorCode();
    }

    private static Status aggregate(NoticeDelivery row, List<NoticeRecipient> entries) {
        boolean success = false;
        boolean skipped = false;
        boolean failed = row.getPreparedAt() == null;
        for (var entry : entries) {
            if (!row.getKind().taskMail()) {
                success |= entry.getInAppStatus() == InAppStatus.WRITTEN;
                skipped |= entry.getInAppStatus() == InAppStatus.SKIPPED;
                failed |= entry.getInAppStatus() == InAppStatus.FAILED;
            }
            success |= entry.getMailStatus() == MailStatus.ACCEPTED;
            skipped |= entry.getMailStatus() == MailStatus.SKIPPED;
            failed |= entry.getMailStatus() == MailStatus.FAILED || entry.getMailStatus() == MailStatus.UNKNOWN;
        }
        if (success) {
            return skipped || failed ? Status.PARTIAL : Status.DONE;
        }
        return failed ? Status.FAILED : Status.SKIPPED;
    }

    private boolean terminatePreparation(NoticeDelivery row, Status status, String code) {
        WorkflowNoticeQueue.terminate(row, status, code, now());
        deliveries.update(row, false);
        if (status == Status.FAILED) {
            log.warn("流程通知方案无法固定且已结案，请核对场景类型、发布版本和收件人解析配置，deliveryId={} instanceId={} eventId={} taskId={} sceneCode={} sceneVersion={} errorCode={}",
                    row.getId(), row.getFlowInstanceId(), row.getEventId(), row.getTaskId(), row.getSceneCode(),
                    row.getSceneVersion(), code);
        } else {
            log.info("流程通知固定前结束，deliveryId={} eventId={} sceneCode={} status={} errorCode={}",
                    row.getId(), row.getEventId(), row.getSceneCode(), status, code);
        }
        return true;
    }

    private void skipMail(NoticeRecipient recipient, String code) {
        recipient.setMailStatus(MailStatus.SKIPPED);
        recipient.setMailErrorCode(code);
        updateRecipient(recipient);
    }

    private void updateRecipient(NoticeRecipient recipient) {
        recipient.setUpdatedAt(now());
        recipients.update(recipient, false);
    }

    private NoticeRecipient recipient(long id, int userId) {
        return recipients.selectOneByQuery(forDelivery(id).and(NOTICE_RECIPIENT.RECIPIENT_USER_ID.eq(userId)));
    }

    private static QueryWrapper forDelivery(long id) {
        return QueryWrapper.create().where(NOTICE_RECIPIENT.DELIVERY_ID.eq(id));
    }

    private NoticeDelivery owned(Claim claim) {
        var row = lock(claim.id());
        var now = now();
        if (!owns(row, claim, now)) {
            throw new LeaseLost();
        }
        if (!row.getLeaseExpiresAt().isAfter(now.plusSeconds(LEASE_SECONDS / 2))) {
            row.setLeaseExpiresAt(now.plusSeconds(LEASE_SECONDS));
            deliveries.update(row, false);
        }
        return row;
    }

    private static boolean owns(NoticeDelivery row, Claim claim, LocalDateTime now) {
        return row != null && row.getStatus() == Status.PROCESSING && claim.token().equals(row.getLeaseToken())
                && row.getLeaseExpiresAt().isAfter(now);
    }

    private NoticeDelivery lock(long id) {
        return deliveries.selectOneByQuery(QueryWrapper.create().where(NOTICE_DELIVERY.ID.eq(id)).forUpdate());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String rendered(String template, Map<String, String> variables, String fallback, int limit) {
        String value = WorkflowSceneCatalog.render(template, variables);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.codePointCount(0, value.length()) <= limit ? value : value.substring(0, value.offsetByCodePoints(0, limit));
    }

    /**
     * 租约过期或已由另一工作者领取，本次尝试停止保存进度。
     */
    static final class LeaseLost extends RuntimeException {
    }
}
