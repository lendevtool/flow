package me.lendev.flow.workbench.flow;

import static me.lendev.flow.common.exception.BadRequestException.localized;
import static me.lendev.flow.workbench.flow.WorkflowRun.status;
import static me.lendev.flow.workbench.flow.WorkflowRuns.INVALID_STATE;
import static me.lendev.flow.workbench.flow.WorkflowRuns.conflict;
import static me.lendev.flow.workbench.flow.WorkflowRuns.event;
import static me.lendev.flow.workbench.flow.WorkflowRuns.manager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.workbench.flow.FlowModels.RunStatus;
import me.lendev.flow.workbench.flow.WorkflowDefinitionModels.Mode;
import me.lendev.flow.workbench.flow.WorkflowDefinitionModels.Node;
import me.lendev.flow.workbench.flow.WorkflowPeople.PrimaryHandler;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.AssignmentReason;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.AttemptStatus;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Dispatch;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Identity;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Receiver;
import me.lendev.flow.workbench.flow.entity.WorkflowAttempt;
import me.lendev.flow.workbench.flow.entity.WorkflowEvent;
import me.lendev.flow.workbench.flow.entity.WorkflowTask;
import me.lendev.flow.workbench.todo.TodoConflictException;
import me.lendev.flow.workbench.todo.TodoNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 建立、派发和关闭流程节点轮次。节点和待办的更新加入流程事务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class WorkflowStages {

    private final WorkflowRuntimeRepository repository;
    private final WorkflowPeople people;
    private final WorkflowBusinessRegistry modules;
    private final WorkflowRuns runs;
    private final WorkflowTasks tasks;

    /**
     * 关闭当前阶段全部未完成任务并取消流程，返回取消事件。
     */
    WorkflowEvent cancelStage(WorkflowRun flow, int actor, String reason, LocalDateTime now) {
        var root = flow.currentRoot();
        closeStage(flow, AttemptStatus.CANCELLED, actor, reason, now);
        flow.row().setStatus(RunStatus.CANCELLED);
        flow.row().setClosedAt(now);
        log.info("流程阶段取消，instanceId={}，操作人 ID={}", flow.row().getId(), actor);
        return event("CANCELLED", root, null, null, reason);
    }

    /**
     * 管理身份重试待分派节点：仍无人可派时抛出冲突，保持待分派状态。
     *
     * @throws TodoConflictException 节点状态不符或仍无法分派
     */
    WorkflowEvent retry(WorkflowRun flow) {
        if (!manager()) {
            throw new TodoNotFoundException();
        }
        var root = flow.currentRoot();
        if (!status(root, AttemptStatus.WAITING_ASSIGNMENT)) {
            throw conflict(INVALID_STATE);
        }
        var plans = plans(flow, root, Map.of());
        repository.recallFor(flow.row().getId(), root.getId()).ifPresent(event ->
                applyRecallDispatch(flow, plans, taskById(flow, event.getTaskId())));
        requireMultiRoleReceivers(plans);
        if (plans.values().stream().anyMatch(plan -> plan.receivers().isEmpty())) {
            throw conflict("error.workflow.assignment");
        }
        dispatch(flow, root, plans);
        log.info("流程分派重试成功，instanceId={}，nodeInstanceId={}", flow.row().getId(), root.getId());
        return event("ASSIGNED", root, root, null, null);
    }

    /**
     * 驳回后重新激活节点：selected 指定重办的子节点，其余已完成子节点复用。
     */
    WorkflowAttempt activateReturned(WorkflowRun flow, Node root, String selected) {
        return activate(flow, root, selected, null, Map.of(), AssignmentReason.RETURNED);
    }

    /**
     * 收回后重开节点，补发普通待办提醒。
     */
    WorkflowAttempt activateRecalled(WorkflowRun flow, Node root, String selected, WorkflowTask recalled) {
        return activate(flow, root, selected, recalled, Map.of(), AssignmentReason.NORMAL);
    }

    /**
     * 发起或提交时使用同一事务内已经校验的主办配置创建待办。
     */
    WorkflowAttempt activate(WorkflowRun flow, Node root, Map<String, PrimaryHandler> primaryHandlers) {
        return activate(flow, root, null, null, primaryHandlers, AssignmentReason.NORMAL);
    }

    private WorkflowAttempt activate(WorkflowRun flow, Node root, String selected, WorkflowTask recalled,
                                     Map<String, PrimaryHandler> primaryHandlers, AssignmentReason reason) {
        var oldRoot = flow.latest(root.key()).orElse(null);
        var parent = newAttempt(flow, root, null, root.order(), reason);
        var children = flow.children(root.key());
        for (Node child : children) {
            if (selected != null && !child.key().equals(selected) && oldRoot != null) {
                var completed = latestCompletedChild(flow, oldRoot, child);
                if (completed.isPresent()) {
                    repository.reuse(parent.getId(), completed.get().getId());
                    continue;
                }
            }
            newAttempt(flow, child, parent.getId(), root.order(), reason);
        }
        var plans = plans(flow, parent, primaryHandlers);
        applyRecallDispatch(flow, plans, recalled);
        log.info("流程节点轮次激活，instanceId={}，nodeKey={}，attemptNo={}", flow.row().getId(), root.key(),
                parent.getAttemptNo());
        // 与当前提交共用事务，校验失败会回滚审批、字段和已关闭的待办。
        requireMultiRoleReceivers(plans);
        if (plans.values().stream().allMatch(plan -> !plan.receivers().isEmpty())) {
            dispatch(flow, parent, plans);
        } else {
            log.warn("流程节点进入待分派，请核对未分派节点的角色办理人、账号启用状态和业务办理权限，修正后执行重试分派，requestId={} instanceId={} nodeInstanceId={} nodeKey={} attemptNo={} unassignedNodeIds={}",
                    AuditParticipants.currentRequestId(), flow.row().getId(), parent.getId(),
                    root.key(), parent.getAttemptNo(), plans.entrySet().stream()
                            .filter(entry -> entry.getValue().receivers().isEmpty())
                            .map(entry -> entry.getKey().getId()).toList());
            plans.forEach((attempt, plan) -> {
                attempt.setRoleId(plan.roleId());
                attempt.setRoleName(plan.roleName());
                repository.update(attempt);
            });
        }
        return parent;
    }

    private static void requireMultiRoleReceivers(Map<WorkflowAttempt, Dispatch> plans) {
        var missing = plans.values().stream().flatMap(plan -> plan.missingRoles().stream()).distinct().toList();
        if (!missing.isEmpty()) {
            throw localized("error.workflow.multi-role-unassigned", String.join("、", missing));
        }
    }

    private Optional<WorkflowAttempt> latestCompletedChild(WorkflowRun flow, WorkflowAttempt old, Node child) {
        var reused = repository.reused(old.getId());
        return flow.attempts().stream().filter(attempt -> child.key().equals(attempt.getNodeKey())
                && (Objects.equals(old.getId(), attempt.getParentNodeInstanceId()) || reused.contains(attempt.getId()))
                && status(attempt, AttemptStatus.DONE)).findFirst();
    }

    private Dispatch recalledDispatch(WorkflowRun flow, WorkflowTask task, Dispatch normal) {
        var old = flow.attempt(task.getNodeInstanceId());
        if (flow.node(old.getNodeKey()).countersign()) {
            if (!people.enabled(task.getAssigneeUserId())) {
                return new Dispatch(old.getRoleId(), old.getRoleName(), List.of());
            }
            return new Dispatch(old.getRoleId(), old.getRoleName(),
                    List.of(new Receiver(task.getAssigneeUserId(), Identity.COUNTERSIGN, null)));
        }
        return normal;
    }

    private void applyRecallDispatch(WorkflowRun flow, Map<WorkflowAttempt, Dispatch> plans, WorkflowTask recalled) {
        if (recalled != null) {
            var recalledNode = flow.attempt(recalled.getNodeInstanceId()).getNodeKey();
            plans.replaceAll((attempt, plan) -> attempt.getNodeKey().equals(recalledNode)
                    ? authorizedDispatch(flow, recalledDispatch(flow, recalled, plan)) : plan);
        }
    }

    private static WorkflowTask taskById(WorkflowRun flow, Long taskId) {
        return flow.tasks().stream().filter(task -> Objects.equals(task.getId(), taskId)).findFirst().orElse(null);
    }

    private WorkflowAttempt newAttempt(WorkflowRun flow, Node node, Long parentId, int stageNumber,
                                       AssignmentReason reason) {
        var row = new WorkflowAttempt();
        row.setFlowInstanceId(flow.row().getId());
        row.setNodeKey(node.key());
        row.setParentNodeInstanceId(parentId);
        row.setStageNo(stageNumber);
        row.setAttemptNo(flow.latest(node.key()).map(previous -> previous.getAttemptNo() + 1).orElse(1));
        row.setMode(node.mode() == null ? null : node.mode().name());
        row.setStatus(AttemptStatus.WAITING_ASSIGNMENT.name());
        row.setAssignmentReason(reason.name());
        repository.insert(row);
        flow.attempts().add(row);
        return row;
    }

    private Map<WorkflowAttempt, Dispatch> plans(WorkflowRun flow, WorkflowAttempt root,
                                                 Map<String, PrimaryHandler> primaryHandlers) {
        var result = new LinkedHashMap<WorkflowAttempt, Dispatch>();
        for (var node : flow.attemptsFor(root)) {
            var def = flow.node(node.getNodeKey());
            if (def.mode() != null) {
                var primary = def.mode() == Mode.PRIMARY && def.roleCode() != null ? primaryHandlers.get(def.roleCode()) : null;
                var now = people.now();
                result.put(node, primary == null ? dispatchPlan(flow, def, now)
                        : authorizedDispatch(flow, people.primaryDispatch(primary, now)));
            }
        }
        return result;
    }

    /**
     * 计算节点分派计划：多角色会签按角色聚合，其余按单角色模式分派，
     * 业务来源实例同时受模块授权过滤。
     */
    Dispatch dispatchPlan(WorkflowRun flow, Node node, LocalDateTime now) {
        if (node.mode() == Mode.MULTI_ALL) {
            return people.multiRoleDispatch(node.handlingRoles(), user -> runs.mayHandle(flow, user));
        }
        return authorizedDispatch(flow, people.singleRoleDispatch(node, now));
    }

    private Dispatch authorizedDispatch(WorkflowRun flow, Dispatch plan) {
        var source = flow.source();
        if (source == null) {
            return plan;
        }
        var module = modules.require(source.module());
        var receivers = plan.receivers().stream().filter(row -> module.mayCreate(source, row.userId())).toList();
        if (plan.receivers().stream().anyMatch(row -> row.identity() == Identity.PRIMARY)
                && receivers.stream().noneMatch(row -> row.identity() == Identity.PRIMARY)) {
            receivers = List.of();
        }
        return new Dispatch(plan.roleId(), plan.roleName(), receivers);
    }

    private void dispatch(WorkflowRun flow, WorkflowAttempt root, Map<WorkflowAttempt, Dispatch> plans) {
        var now = people.now();
        plans.forEach((attempt, plan) -> {
            attempt.setRoleId(plan.roleId());
            attempt.setRoleName(plan.roleName());
            attempt.setActivatedAt(now);
            set(attempt, AttemptStatus.ACTIVE, null);
            var reason = AssignmentReason.RETURNED.name().equals(attempt.getAssignmentReason())
                    ? AssignmentReason.RETURNED : AssignmentReason.NORMAL;
            Long primary = null;
            for (var person : plan.receivers()) {
                var task = tasks.createTask(flow, attempt, person, person.identity() == Identity.DELEGATE ? primary : null, reason);
                if (person.identity() == Identity.PRIMARY) {
                    primary = task.getId();
                }
            }
        });
        if (!plans.containsKey(root)) {
            root.setActivatedAt(now);
            set(root, AttemptStatus.ACTIVE, null);
        }
        log.info("流程节点分派完成，instanceId={}，nodeInstanceId={}，任务数={}", flow.row().getId(), root.getId(),
                plans.values().stream().mapToInt(plan -> plan.receivers().size()).sum());
    }

    /**
     * 关闭当前阶段所有办理中或待分派的节点及其未完成任务。
     */
    void closeStage(WorkflowRun flow, AttemptStatus status, int actor, String reason, LocalDateTime now) {
        var root = flow.currentRoot();
        for (var attempt : flow.attemptsFor(root)) {
            if (status(attempt, AttemptStatus.ACTIVE) || status(attempt, AttemptStatus.WAITING_ASSIGNMENT)) {
                tasks.closePending(flow, attempt, actor, reason);
                set(attempt, status, now);
            }
        }
    }

    /**
     * 更新节点状态并落库，关闭时记录关闭时间。
     */
    void set(WorkflowAttempt attempt, AttemptStatus status, LocalDateTime closedAt) {
        attempt.setStatus(status.name());
        attempt.setClosedAt(closedAt);
        repository.update(attempt);
    }
}
