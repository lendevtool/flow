package me.lendev.flow.pms.workflownotification;

import static me.lendev.flow.pms.workflownotification.WorkflowNoticeModels.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.pms.notificationgroup.WorkflowSceneCatalog;
import me.lendev.flow.pms.notificationgroup.WorkflowSceneCatalog.Scene;
import me.lendev.flow.pms.workflownotification.mapper.NoticeDeliveryMapper;
import me.lendev.flow.pms.workflownotification.mapper.NoticeRecipientMapper;
import me.lendev.flow.pms.workflownotification.persistence.NoticeDelivery;
import me.lendev.flow.pms.workflownotification.persistence.NoticeRecipient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 在流转事务中登记附加通知及已写站内消息的可选邮件。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowNoticeQueue {

    private final WorkflowSceneCatalog scenes;
    private final NoticeDeliveryMapper deliveries;
    private final NoticeRecipientMapper recipients;
    private final ObjectMapper json;
    private final Clock clock;

    /**
     * 流程动作发生后用于登记节点附加通知的最小上下文。
     */
    public record SceneTrigger(long flowInstanceId, long nodeInstanceId, long eventId, String sceneCode,
                               boolean rejected, String nodeName, String targetNodeName) {
    }

    /**
     * 新待办与同步站内消息已写入后使用的可选邮件上下文。
     */
    public record TaskTrigger(long flowInstanceId, long nodeInstanceId, long taskId, Long eventId,
                              int userId, long messageId, boolean returned, String nodeName) {
    }

    /**
     * 以场景编码顺序锁定配置并记录当前启用周期。与调用方的流程事件事务一起提交；
     * 重复触发保留原投递。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueScenes(List<SceneTrigger> triggers) {
        var configs = new TreeMap<String, Scene>();
        triggers.forEach(trigger -> configs.put(trigger.sceneCode(), null));
        configs.replaceAll((code, ignored) -> scenes.lock(code));
        for (var trigger : triggers) {
            var scene = configs.get(trigger.sceneCode());
            var now = now();
            String action = trigger.rejected() ? "REJECTED" : "APPROVED";
            var kind = trigger.rejected() ? Kind.NODE_REJECTED : Kind.NODE_APPROVED;
            String key = "EVENT:" + trigger.eventId() + ":NODE:" + trigger.nodeInstanceId() + ":" + action;
            var variables = variables(trigger.rejected() ? "驳回" : "通过", trigger.nodeName(), trigger.targetNodeName());
            var source = new DeliverySource(trigger.flowInstanceId(), trigger.nodeInstanceId(), trigger.eventId(), null);
            var context = new EventContext(scene == null ? 0 : scene.activationVersion(), variables);
            var row = delivery(key, source, kind, trigger.sceneCode(), context, now);
            if (scene == null || !WorkflowSceneCatalog.EXTRA.equals(scene.type())) {
                terminate(row, Status.FAILED, "SCENE_CONFIGURATION_INVALID", now);
            } else if (!scene.enabled()) {
                terminate(row, Status.SKIPPED, "SCENE_DISABLED", now);
            }
            insert(row);
        }
    }

    /**
     * 只在系统场景已配置邮件模板时登记，固定模板 ID 和唯一收件人；
     * 与调用方写入待办及站内消息的事务一起提交。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueTaskMail(TaskTrigger trigger) {
        String code = trigger.returned() ? "workflow.task.returned" : "workflow.task.assigned";
        var scene = scenes.lock(code);
        if (scene == null || scene.mailTemplateId() == null) {
            return;
        }
        var now = now();
        String action = trigger.returned() ? "RETURNED" : "ASSIGNED";
        String key = "TASK:" + trigger.taskId() + ":" + action + "_MAIL";
        var kind = trigger.returned() ? Kind.TASK_RETURNED_MAIL : Kind.TASK_ASSIGNED_MAIL;
        var variables = variables(trigger.returned() ? "退回待办" : "新待办", trigger.nodeName(), null);
        var source = new DeliverySource(trigger.flowInstanceId(), trigger.nodeInstanceId(), trigger.eventId(), trigger.taskId());
        var row = delivery(key, source, kind, code, new EventContext(scene.activationVersion(), variables), now);
        freeze(row, scene.version(), new Plan(null, null, scene.mailTemplateId(), variables), now, json);
        if (insert(row)) {
            recipients.insert(recipient(row.getId(), trigger.userId(), trigger.messageId(), true, now));
        }
    }

    private NoticeDelivery delivery(String key, DeliverySource source, Kind kind, String sceneCode,
                                    EventContext context, LocalDateTime now) {
        var row = new NoticeDelivery();
        row.setTriggerKey(key);
        row.setFlowInstanceId(source.flowInstanceId());
        row.setNodeInstanceId(source.nodeInstanceId());
        row.setEventId(source.eventId());
        row.setTaskId(source.taskId());
        row.setKind(kind);
        row.setSceneCode(sceneCode);
        row.setEventContextJson(json.writeValueAsString(context));
        row.setStatus(Status.PENDING);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private boolean insert(NoticeDelivery row) {
        try {
            deliveries.insert(row);
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
        log.info("流程通知已登记，requestId={} deliveryId={} eventId={} taskId={} sceneCode={} sceneVersion={} status={}",
                AuditParticipants.currentRequestId(), row.getId(), row.getEventId(), row.getTaskId(), row.getSceneCode(),
                row.getSceneVersion(), row.getStatus());
        return true;
    }

    static NoticeRecipient recipient(long deliveryId, int userId, Long messageId, boolean mail, LocalDateTime now) {
        var recipient = new NoticeRecipient();
        recipient.setDeliveryId(deliveryId);
        recipient.setRecipientUserId(userId);
        recipient.setMessageId(messageId);
        recipient.setInAppStatus(messageId == null ? InAppStatus.PENDING : InAppStatus.WRITTEN);
        recipient.setMailStatus(mail ? MailStatus.PENDING : MailStatus.OFF);
        recipient.setUpdatedAt(now);
        return recipient;
    }

    static void freeze(NoticeDelivery row, long version, Plan plan, LocalDateTime now, ObjectMapper json) {
        row.setSceneVersion(version);
        row.setPlanJson(json.writeValueAsString(plan));
        row.setPreparedAt(now);
        row.setUpdatedAt(now);
    }

    static void terminate(NoticeDelivery row, Status status, String code, LocalDateTime now) {
        row.setStatus(status);
        row.setLastErrorCode(code);
        row.setFinishedAt(now);
        row.setLeaseToken(null);
        row.setLeaseExpiresAt(null);
        row.setNextAttemptAt(null);
        row.setUpdatedAt(now);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static Map<String, String> variables(String action, String node, String target) {
        return Map.of("event.type", action, "node.name", Objects.toString(node, ""),
                "targetNode.name", Objects.toString(target, ""));
    }

    private record DeliverySource(long flowInstanceId, long nodeInstanceId, Long eventId, Long taskId) {
    }
}
