package me.lendev.flow.workbench.flow;

import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO_FLOW_MANAGE;
import static me.lendev.flow.workbench.flow.WorkflowRun.status;

import cn.hutool.crypto.digest.DigestUtil;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.AuditSnapshots;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.workbench.flow.FlowModels.RunStatus;
import me.lendev.flow.workbench.flow.WorkflowDefinitionModels.View;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.AttemptStatus;
import me.lendev.flow.workbench.flow.entity.FlowInstance;
import me.lendev.flow.workbench.flow.entity.WorkflowAttempt;
import me.lendev.flow.workbench.flow.entity.WorkflowEvent;
import me.lendev.flow.workbench.flow.entity.WorkflowTask;
import me.lendev.flow.workbench.todo.TodoAccess;
import me.lendev.flow.workbench.todo.TodoConflictException;
import me.lendev.flow.workbench.todo.TodoNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 加载并校验流程实例，保存状态变更和审计事件。写入操作由调用方持有实例锁及事务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class WorkflowRuns {

    static final String INVALID_STATE = "error.flow.state";
    static final String REQUEST_KEY_CONFLICT = "error.todo.request-key";
    private static final String WAITING_ASSIGNMENT = "WAITING_ASSIGNMENT";
    private final WorkflowRuntimeRepository repository;
    private final WorkflowDefinitionRepository definitions;
    private final WorkflowBusinessDelivery delivery;
    private final WorkflowRuntimeNotices notices;
    private final WorkflowBusinessRegistry modules;
    private final AuditSnapshots audit;

    /**
     * 加载实例并校验当前人可见性，lock 为 true 时锁定实例主行。
     *
     * @throws TodoNotFoundException 实例不存在或当前人不可见
     */
    WorkflowRun visible(long id, int actor, boolean lock) {
        var flow = load(id, lock);
        if (!flow.visible(actor, manager())) {
            throw new TodoNotFoundException();
        }
        return flow;
    }

    /**
     * 加载实例、定义快照、节点轮次与任务，不校验可见性。
     */
    WorkflowRun load(long id, boolean lock) {
        var row = repository.find(id, lock).orElseThrow(TodoNotFoundException::new);
        var definition = definitions.find(row.getDefinitionId(), false).orElseThrow(TodoNotFoundException::new);
        return load(row, definition);
    }

    WorkflowRun load(FlowInstance row, View definition) {
        var attempts = repository.attempts(row.getId());
        return new WorkflowRun(row, definition, new ArrayList<>(attempts),
                new ArrayList<>(repository.tasks(attempts.stream().map(WorkflowAttempt::getId).toList())));
    }

    /**
     * 判断用户可否参与流程：工作台实例恒可，业务实例由模块授权决定。
     */
    boolean mayHandle(WorkflowRun flow, int actor) {
        var source = flow.source();
        return source == null || modules.require(source.module()).mayCreate(source, actor);
    }

    /**
     * 是否为发起人或流程管理人。
     */
    static boolean ownsOrManages(WorkflowRun flow, int actor) {
        return Objects.equals(flow.row().getOwnerUserId(), actor) || manager();
    }

    static boolean manager() {
        return TodoAccess.can(WORKBENCH_TODO_FLOW_MANAGE);
    }

    /**
     * 查找当前用户可收回的推进事件：当前阶段无人处理且由本人推进。
     */
    Optional<WorkflowEvent> recallable(WorkflowRun flow, int actor) {
        if (flow.row().getStatus() != RunStatus.RUNNING || flow.row().getCurrentStepNo() <= 1) {
            return Optional.empty();
        }
        var root = flow.currentRoot();
        boolean handled = flow.attemptsFor(root).stream().flatMap(item -> flow.tasksFor(item.getId()).stream())
                .anyMatch(task -> task.getProcessedAt() != null);
        return handled ? Optional.empty() : repository.advancement(flow.row().getId(), root.getId())
                .filter(event -> event.getActorUserId() == actor);
    }

    /**
     * 请求键幂等检查：命中且摘要一致返回 true，摘要不一致抛出冲突。
     *
     * @throws TodoConflictException 相同请求键携带了不同内容
     */
    boolean replayed(long id, String key, String hash) {
        var event = repository.request(id, key);
        if (event.isEmpty()) {
            return false;
        }
        if (!hash.equals(event.get().getRequestHash())) {
            log.info("流程事件幂等键已对应其他内容，requestId={} instanceId={} eventId={}",
                    AuditParticipants.currentRequestId(), id, event.get().getId());
            throw conflict(REQUEST_KEY_CONFLICT);
        }
        return true;
    }

    /**
     * 校验流程运行中且版本匹配。
     *
     * @throws TodoConflictException 流程已结束或版本不匹配
     */
    static void verifyVersion(WorkflowRun flow, Long version) {
        if (flow.row().getStatus() != RunStatus.RUNNING) {
            throw conflict(INVALID_STATE);
        }
        if (!Objects.equals(flow.row().getVersion(), version)) {
            log.info("流程实例版本冲突，instanceId={}，期望版本={}，当前版本={}", flow.row().getId(), version,
                    flow.row().getVersion());
            throw conflict("error.todo.version");
        }
    }

    /**
     * 递增版本保存实例状态，并写入事件与投递队列。
     */
    void update(WorkflowRun flow, WorkflowEvent event, int actor, String key, String hash, LocalDateTime now) {
        flow.row().setVersion(flow.row().getVersion() + 1);
        flow.row().setUpdatedAt(now);
        repository.update(flow.row());
        saveEvent(flow, event, actor, key, hash, now);
    }

    /**
     * 写入事件并入队业务投递；业务实例推进后若新阶段待分派，追加 WAITING_ASSIGNMENT 事件。
     */
    void saveEvent(WorkflowRun flow, WorkflowEvent event, int actor, String key, String hash, LocalDateTime now) {
        event.setFlowInstanceId(flow.row().getId());
        event.setActorUserId(actor);
        event.setRequestKey(key);
        event.setRequestHash(hash);
        event.setCreatedAt(now);
        repository.insert(event);
        log.info("流程事件保存，instanceId={}，eventId={}，type={}", flow.row().getId(), event.getId(),
                event.getEventType());
        notices.enqueueTaskMails(flow.taskNoticeMails(), event.getId());
        flow.taskNoticeMails().clear();
        delivery.enqueue(flow, event);
        if (!WAITING_ASSIGNMENT.equals(event.getEventType()) && event.getToNodeInstanceId() != null && flow.source() != null
                && status(flow.currentRoot(), AttemptStatus.WAITING_ASSIGNMENT)) {
            var waiting = event(WAITING_ASSIGNMENT, null, flow.currentRoot(), null, null);
            saveEvent(flow, waiting, actor, " WAIT:" + event.getId(), hash, now);
        }
        if (AuditParticipants.active()) {
            var reference = new AuditObject("WORKFLOW_EVENT", Long.toString(event.getId()), event.getEventType(),
                    flow.row().getTitle(), "WORKFLOW_INSTANCE", Long.toString(flow.row().getId()));
            AuditParticipants.addChanges(List.of(audit.reference(reference)));
        }
    }

    /**
     * 构造未落库的事件对象。
     */
    static WorkflowEvent event(String type, WorkflowAttempt from, WorkflowAttempt to, WorkflowTask task, String reason) {
        var event = new WorkflowEvent();
        event.setEventType(type);
        event.setFromNodeInstanceId(from == null ? null : from.getId());
        event.setToNodeInstanceId(to == null ? null : to.getId());
        event.setTaskId(task == null ? null : task.getId());
        event.setReason(reason);
        return event;
    }

    /**
     * 计算请求内容的幂等摘要。
     */
    String hash(Object request) {
        return DigestUtil.sha256Hex(repository.json(request));
    }

    static TodoConflictException conflict(String state) {
        return new TodoConflictException(state);
    }
}
