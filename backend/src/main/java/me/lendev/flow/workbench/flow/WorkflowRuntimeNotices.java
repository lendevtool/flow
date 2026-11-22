package me.lendev.flow.workbench.flow;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.pms.inapp.InAppMessageService;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeQueue;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeQueue.SceneTrigger;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeQueue.TaskTrigger;
import me.lendev.flow.workbench.flow.WorkflowDefinitionModels.Trigger;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.AssignmentReason;
import me.lendev.flow.workbench.flow.entity.WorkflowAttempt;
import me.lendev.flow.workbench.flow.entity.WorkflowEvent;
import me.lendev.flow.workbench.flow.entity.WorkflowTask;
import org.springframework.stereotype.Component;

/**
 * 将真实任务和节点状态变化转换为站内提醒及通知投递登记。
 */
@Component
@RequiredArgsConstructor
@Slf4j
class WorkflowRuntimeNotices {

    private static final String SOURCE = "WORKFLOW";
    private static final String TASK_ASSIGNED = "TASK_ASSIGNED";
    private static final String TASK_RETURNED = "TASK_RETURNED";
    private final InAppMessageService inbox;
    private final WorkflowNoticeQueue queue;

    /**
     * 插入流程任务后立即保存站内提醒，邮件登记等候同事务内的流程事件 ID。
     */
    void assigned(WorkflowRun flow, WorkflowAttempt attempt, WorkflowTask task, AssignmentReason reason) {
        boolean returned = reason == AssignmentReason.RETURNED;
        String nodeName = flow.node(attempt.getNodeKey()).title();
        String kind = returned ? TASK_RETURNED : TASK_ASSIGNED;
        long messageId = inbox.publish(new InAppMessageService.Write(task.getAssigneeUserId(), SOURCE,
                "TASK:" + task.getId() + ":" + kind, kind, returned ? "退回待办提醒" : "新待办提醒",
                (returned ? "请重新办理节点「" : "请办理节点「") + nodeName + "」。", "WORKFLOW_TASK", task.getId()));
        flow.taskNoticeMails().add(new TaskTrigger(flow.row().getId(), attempt.getId(), task.getId(), null,
                task.getAssigneeUserId(), messageId, returned, nodeName));
        log.info("已保存流程待办站内提醒，requestId={} instanceId={} taskId={} messageId={} type={}",
                AuditParticipants.currentRequestId(), flow.row().getId(), task.getId(), messageId, kind);
    }

    /**
     * 旧版节点只发送站内提醒，并按统一待办 ID 去重。
     */
    void legacyAssigned(long todoId, int assigneeId, String nodeName) {
        long messageId = inbox.publish(new InAppMessageService.Write(assigneeId, SOURCE,
                "TODO:" + todoId + ":" + TASK_ASSIGNED, TASK_ASSIGNED, "新待办提醒",
                "请办理节点「" + nodeName + "」。", "WORKBENCH_TODO", todoId));
        log.info("已保存旧版流程待办站内提醒，requestId={} todoId={} messageId={}",
                AuditParticipants.currentRequestId(), todoId, messageId);
    }

    /**
     * 事件写入后登记同次分派的可选邮件，直接委托补发时 eventId 为空。
     */
    void enqueueTaskMails(List<TaskTrigger> triggers, Long eventId) {
        for (var trigger : triggers) {
            queue.enqueueTaskMail(new TaskTrigger(trigger.flowInstanceId(), trigger.nodeInstanceId(), trigger.taskId(),
                    eventId, trigger.userId(), trigger.messageId(), trigger.returned(), trigger.nodeName()));
        }
    }

    /**
     * 提交动作仅登记实际办结的轮次，驳回动作仅登记执行驳回的来源轮次。
     */
    void enqueueActions(WorkflowRun flow, WorkflowEvent event, List<WorkflowAttempt> completed) {
        Stream<SceneTrigger> triggers = completed.stream()
                .map(attempt -> scene(flow, event, attempt, Trigger.APPROVED));
        if ("RETURNED".equals(event.getEventType())) {
            var source = flow.attempt(event.getFromNodeInstanceId());
            triggers = Stream.concat(triggers, Stream.of(scene(flow, event, source, Trigger.REJECTED)));
        }
        var configured = triggers.filter(Objects::nonNull).toList();
        if (!configured.isEmpty()) {
            queue.enqueueScenes(configured);
            log.info("已登记流程节点通知，requestId={} instanceId={} eventId={} sceneCount={}",
                    AuditParticipants.currentRequestId(), flow.row().getId(), event.getId(), configured.size());
        }
    }

    private static SceneTrigger scene(WorkflowRun flow, WorkflowEvent event, WorkflowAttempt source, Trigger trigger) {
        var node = flow.node(source.getNodeKey());
        String code = node.sceneCode(trigger);
        if (code == null) {
            return null;
        }
        var target = trigger == Trigger.REJECTED ? event.getToNodeInstanceId() : null;
        String targetName = target == null ? "" : flow.node(flow.attempt(target).getNodeKey()).title();
        return new SceneTrigger(flow.row().getId(), source.getId(), event.getId(), code, trigger == Trigger.REJECTED,
                node.title(), targetName);
    }
}
