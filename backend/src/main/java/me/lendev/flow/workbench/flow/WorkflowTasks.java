package me.lendev.flow.workbench.flow;

import static me.lendev.flow.workbench.flow.WorkflowRun.status;
import static me.lendev.flow.workbench.flow.WorkflowRuns.INVALID_STATE;
import static me.lendev.flow.workbench.flow.WorkflowRuns.conflict;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.pms.workflownotification.WorkflowNoticeQueue.TaskTrigger;
import me.lendev.flow.workbench.flow.FlowModels.RunStatus;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.AssignmentReason;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.AttemptStatus;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.DelegatedTodo;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.DelegationDetail;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Forward;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.ForwardResult;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Identity;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Receiver;
import me.lendev.flow.workbench.flow.entity.WorkflowAttempt;
import me.lendev.flow.workbench.flow.entity.WorkflowDelegation;
import me.lendev.flow.workbench.flow.entity.WorkflowTask;
import me.lendev.flow.workbench.todo.TodoAuditHandler;
import me.lendev.flow.workbench.todo.TodoConflictException;
import me.lendev.flow.workbench.todo.TodoModels.Status;
import me.lendev.flow.workbench.todo.TodoModels.Write;
import me.lendev.flow.workbench.todo.TodoNotFoundException;
import me.lendev.flow.workbench.todo.TodoSchedule;
import me.lendev.flow.workbench.todo.TodoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 管理流程任务与统一待办的关联，记录处理结果并处理休假转办补发。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class WorkflowTasks {

    private final WorkflowRuntimeRepository repository;
    private final WorkflowPeople people;
    private final WorkflowRuns runs;
    private final WorkflowAttachments attachments;
    private final TodoSchedule clock;
    private final TodoService todos;
    private final TodoAuditHandler todoAudit;
    private final WorkflowRuntimeNotices notices;

    /**
     * 为接收人创建节点任务与统一待办，待办以 实例+节点+用户 作为幂等键。
     * original 为委托补发时的原任务 ID。
     */
    WorkflowTask createTask(WorkflowRun flow, WorkflowAttempt attempt, Receiver recipient, Long original,
                            AssignmentReason reason) {
        var node = flow.node(attempt.getNodeKey());
        LocalDate due = node.dueOffsetDays() == null ? null : clock.today().plusDays(node.dueOffsetDays());
        var write = new Write(null, node.title(), null, recipient.userId(), "NORMAL", due == null ? "NONE" : "FIXED",
                null, null, due, null, null, null, null, null, flow.dimensions(), null, null);
        var todo = todos.createFlowNode(flow.row().getId(), todoKey(flow, attempt, recipient.userId()),
                flow.row().getOwnerUserId(), flow.row().getOwnerName(), write);
        var task = new WorkflowTask();
        task.setNodeInstanceId(attempt.getId());
        task.setTodoId(todo.id());
        task.setAssigneeUserId(recipient.userId());
        task.setIdentity(recipient.identity().name());
        task.setDelegationId(recipient.delegationId());
        task.setDelegatedFromTaskId(original);
        repository.insert(task);
        notices.assigned(flow, attempt, task, reason);
        if (AuditParticipants.active()) {
            AuditParticipants.addChanges(List.of(todoAudit.todoChange(null, todo)));
        }
        flow.tasks().add(task);
        log.info("流程任务创建，instanceId={}，nodeInstanceId={}，taskId={}，办理人 ID={}，身份={}",
                flow.row().getId(), attempt.getId(), task.getId(), recipient.userId(), recipient.identity());
        return task;
    }

    /**
     * 记录任务处理结果、意见与附件快照，并关闭对应待办。
     */
    void recordResult(WorkflowRun flow, WorkflowTask task, String result, String opinion,
                      Map<String, JsonNode> values, LocalDateTime now) {
        task.setProcessedAt(now);
        task.setProcessedBy(task.getAssigneeUserId());
        task.setOpinion(opinion);
        task.setResult(result);
        task.setAttachmentSnapshotJson(repository.json(attachments.snapshot(flow, values)));
        repository.update(task);
        log.info("流程任务处理完成，instanceId={}，taskId={}，result={}", flow.row().getId(), task.getId(), result);
        closeTodo(flow, task, task.getAssigneeUserId(), Status.DONE, null);
    }

    /**
     * 取消节点下所有未处理任务的待办，已处理任务保持不变。
     */
    void closePending(WorkflowRun flow, WorkflowAttempt attempt, int actor, String reason) {
        for (var task : flow.tasksFor(attempt.getId())) {
            if (task.getProcessedAt() == null) {
                closeTodo(flow, task, actor, Status.CANCELLED, reason);
            }
        }
    }

    private void closeTodo(WorkflowRun flow, WorkflowTask task, int actor, Status target, String reason) {
        var attempt = flow.attempt(task.getNodeInstanceId());
        long flowId = flow.row().getId();
        String key = todoKey(flow, attempt, task.getAssigneeUserId());
        var before = todos.readFlowNode(flowId, key, task.getTodoId());
        var after = todos.closeFlowNode(flowId, key, task.getTodoId(), target, actor, reason);
        if (AuditParticipants.active() && before.status() != after.status()) {
            AuditParticipants.addChanges(List.of(todoAudit.todoChange(before, after)));
        }
    }

    /**
     * 定位当前用户可处理的任务：须为当前阶段办理中节点的未处理任务。
     *
     * @param useTaskId true 按任务 ID 匹配，false 按待办 ID 匹配
     * @throws TodoNotFoundException 任务不存在或当前人无权处理
     * @throws TodoConflictException 流程或节点状态不符
     */
    WorkflowTask openTask(WorkflowRun flow, int actor, Long id, boolean useTaskId) {
        if (!runs.mayHandle(flow, actor)) {
            throw new TodoNotFoundException();
        }
        var task = flow.tasks().stream().filter(item -> Objects.equals(useTaskId ? item.getId() : item.getTodoId(), id)
                && item.getAssigneeUserId() == actor).findFirst().orElseThrow(TodoNotFoundException::new);
        var attempt = flow.attempt(task.getNodeInstanceId());
        if (flow.row().getStatus() != RunStatus.RUNNING || task.getProcessedAt() != null
                || !Objects.equals(attempt.getStageNo(), flow.row().getCurrentStepNo())
                || !status(attempt, AttemptStatus.ACTIVE)) {
            throw conflict(INVALID_STATE);
        }
        return task;
    }

    /**
     * 查询委托详情：生效中且未补发的主办待办为待处理列表。
     */
    DelegationDetail delegation(long id, int actor) {
        var delegation = people.owned(id, actor);
        var forwarded = repository.forwarded(id);
        var originals = forwarded.stream().map(WorkflowTask::getDelegatedFromTaskId).collect(Collectors.toSet());
        var pending = delegation.getCancelledAt() != null || !delegation.getEndsAt().isAfter(people.now())
                ? List.<DelegatedTodo>of() : repository.openPrimaryTasks(actor).stream()
                .filter(item -> !originals.contains(item.getId())).map(this::delegatedTodo).toList();
        return new DelegationDetail(people.view(delegation), pending, forwarded.stream().map(this::delegatedTodo).toList());
    }

    /**
     * 补发时按实例锁重新确认任务仍可处理，并防止收件人重复。
     * 单个待办失败不影响其余，结果逐条返回 CLOSED/ALREADY_FORWARDED/DENIED/FORWARDED。
     *
     * @throws TodoConflictException 委托已失效或受托人停用
     * @throws TodoNotFoundException 待办不存在或非本人主办任务
     */
    @Audited(AuditOperation.TODO_FLOW_ASSIGN)
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public List<ForwardResult> forward(long id, int actor, Forward input) {
        verifyForwarding(people.owned(id, actor));
        var tasks = input.todoIds().stream().distinct().map(todo -> repository.byTodo(todo)
                        .filter(task -> task.getAssigneeUserId() == actor && Identity.PRIMARY.name().equals(task.getIdentity()))
                        .orElseThrow(TodoNotFoundException::new))
                .sorted(Comparator.comparingLong(repository::flowId)).toList();
        var result = new ArrayList<ForwardResult>();
        var mail = new ArrayList<TaskTrigger>();
        for (var original : tasks) {
            var flow = runs.visible(repository.flowId(original), actor, true);
            var delegation = people.owned(id, actor, true);
            verifyForwarding(delegation);
            var stored = flow.tasks().stream().filter(item -> item.getId().equals(original.getId())).findFirst().orElseThrow();
            var attempt = flow.attempt(stored.getNodeInstanceId());
            String outcome;
            if (flow.row().getStatus() != RunStatus.RUNNING || !status(attempt, AttemptStatus.ACTIVE) || stored.getProcessedAt() != null) {
                outcome = "CLOSED";
            } else if (flow.tasksFor(attempt.getId()).stream().anyMatch(item -> item.getAssigneeUserId().equals(delegation.getDelegateUserId()))) {
                outcome = "ALREADY_FORWARDED";
            } else if (!runs.mayHandle(flow, delegation.getDelegateUserId())) {
                outcome = "DENIED";
            } else {
                createTask(flow, attempt, new Receiver(delegation.getDelegateUserId(), Identity.DELEGATE, id),
                        stored.getId(), AssignmentReason.NORMAL);
                mail.addAll(flow.taskNoticeMails());
                outcome = "FORWARDED";
            }
            result.add(new ForwardResult(stored.getTodoId(), outcome));
            log.info("委托待办转交，delegationId={}，todoId={}，结果={}", id, stored.getTodoId(), outcome);
        }
        // 先锁完涉及的实例，再锁场景，保持与单实例流转相同的加锁顺序。
        notices.enqueueTaskMails(mail, null);
        return List.copyOf(result);
    }

    private void verifyForwarding(WorkflowDelegation delegation) {
        if (delegation.getCancelledAt() != null || !delegation.getEndsAt().isAfter(people.now())
                || !people.enabled(delegation.getDelegateUserId())) {
            throw conflict("error.workflow.delegation");
        }
    }

    private DelegatedTodo delegatedTodo(WorkflowTask task) {
        var row = repository.find(repository.flowId(task), false).orElseThrow(TodoNotFoundException::new);
        return new DelegatedTodo(task.getTodoId(), row.getId(), row.getTitle());
    }

    /**
     * 新版节点待办幂等键：v2:实例:节点轮次:用户。
     */
    private static String todoKey(WorkflowRun flow, WorkflowAttempt attempt, int user) {
        return "v2:" + flow.row().getId() + ":" + attempt.getId() + ":" + user;
    }
}
