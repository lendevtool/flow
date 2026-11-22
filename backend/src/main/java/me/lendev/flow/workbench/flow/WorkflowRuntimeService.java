package me.lendev.flow.workbench.flow;

import static me.lendev.flow.common.exception.BadRequestException.localized;
import static me.lendev.flow.workbench.flow.WorkflowRun.status;
import static me.lendev.flow.workbench.flow.WorkflowRuns.INVALID_STATE;
import static me.lendev.flow.workbench.flow.WorkflowRuns.conflict;
import static me.lendev.flow.workbench.flow.WorkflowRuns.event;
import static me.lendev.flow.workbench.flow.WorkflowRuns.ownsOrManages;
import static me.lendev.flow.workbench.flow.WorkflowRuns.verifyVersion;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.document.DocumentCenter;
import me.lendev.flow.field.FieldModels;
import me.lendev.flow.field.FieldValidator;
import me.lendev.flow.workbench.flow.FlowModels.RunStatus;
import me.lendev.flow.workbench.flow.WorkflowDefinitionModels.Mode;
import me.lendev.flow.workbench.flow.WorkflowDefinitionModels.Node;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Action;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Attachment;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.AttemptStatus;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Detail;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Operation;
import me.lendev.flow.workbench.flow.entity.WorkflowAttempt;
import me.lendev.flow.workbench.flow.entity.WorkflowEvent;
import me.lendev.flow.workbench.todo.TodoConflictException;
import me.lendev.flow.workbench.todo.TodoNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 处理正在运行的流程及其附件。实例锁将节点处理、收回、驳回和待办补发串行化。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class WorkflowRuntimeService {

    private static final String ALREADY_HANDLED = "已由其他处理人提交";
    private final WorkflowRuntimeRepository repository;
    private final WorkflowRuns runs;
    private final WorkflowStages stages;
    private final WorkflowTasks tasks;
    private final WorkflowQueries queries;
    private final WorkflowPeople people;
    private final WorkflowAttachments attachments;
    private final FieldValidator validator;
    private final WorkflowRuntimeNotices notices;
    private final WorkflowBusinessRegistry modules;

    /**
     * 将处理、驳回、收回等动作及关联待办变更一起提交。
     * 请求键幂等：同键同内容重放当前详情，同键不同内容抛出冲突。
     *
     * @param id     流程实例 ID
     * @param actor  当前操作人的用户 ID
     * @param action 要执行的运行操作
     * @param input  请求键、预期版本及与动作相关的待办、退回目标或表单值
     * @return 操作完成后的流程详情，幂等重放返回实例当前详情
     * @throws TodoConflictException 版本不匹配、状态不符或请求键冲突
     * @throws TodoNotFoundException 实例或任务不存在、当前人无权操作
     */
    @Audited(AuditOperation.WORKFLOW_ACTION)
    @Transactional(rollbackFor = Exception.class)
    public Detail act(long id, int actor, Operation action, Action input) {
        var flow = runs.visible(id, actor, true);
        var normalized = new Action(input.requestKey(), input.version(), input.todoId(), input.targetNodeKey(),
                input.reason(), input.opinion(), input.fields() == null ? Map.of() : new TreeMap<>(input.fields()));
        String hash = runs.hash(List.of(actor, action, normalized));
        if (runs.replayed(id, input.requestKey(), hash)) {
            return queries.detail(flow, actor);
        }
        verifyVersion(flow, input.version());
        log.info("流程运行操作开始，requestId={} instanceId={} action={} actorId={} todoId={} expectedVersion={}",
                AuditParticipants.currentRequestId(), id, action, actor, input.todoId(), input.version());
        var now = people.now();
        var completed = new ArrayList<WorkflowAttempt>(2);
        var event = switch (action) {
            case SUBMIT -> submit(flow, actor, input, now, completed);
            case REJECT -> reject(flow, actor, input, now);
            case RECALL -> recall(flow, actor, now);
            case CANCEL -> cancel(flow, actor, input, now);
            case RETRY_ASSIGNMENT -> stages.retry(flow);
        };
        runs.update(flow, event, actor, input.requestKey(), hash, now);
        notices.enqueueActions(flow, event, completed);
        return queries.detail(runs.load(flow.row(), flow.definition()), actor);
    }

    /**
     * 提交/审批：校验字段与附件，会签未齐时仅记录个人结果，
     * 全部完成后推进下一阶段或结束流程。
     */
    private WorkflowEvent submit(WorkflowRun flow, int actor, Action input, LocalDateTime now,
                                 List<WorkflowAttempt> completed) {
        var task = tasks.openTask(flow, actor, input.todoId(), false);
        var primaryHandlers = people.requirePrimaryHandlers(flow.definition().nodes());
        var attempt = flow.attempt(task.getNodeInstanceId());
        var node = flow.node(attempt.getNodeKey());
        var current = repository.values(flow.row().getFieldValuesJson());
        var patch = fields(flow, node, current, input.fields());
        attachments.bind(flow, task, current, patch);
        var merged = new LinkedHashMap<>(current);
        merged.putAll(patch);
        flow.row().setFieldValuesJson(repository.json(merged));
        String result = attempt.getStageNo() == 1 && node.mode() != Mode.MULTI_ALL ? "SUBMIT" : "APPROVE";
        tasks.recordResult(flow, task, result, input.opinion(), merged, now);
        if (node.countersign() && flow.tasksFor(attempt.getId()).stream().anyMatch(item -> item.getProcessedAt() == null)) {
            return event("APPROVED", attempt, null, task, null);
        }
        tasks.closePending(flow, attempt, actor, ALREADY_HANDLED);
        stages.set(attempt, AttemptStatus.DONE, now);
        completed.add(attempt);
        if (attempt.getParentNodeInstanceId() != null) {
            var parent = flow.attempt(attempt.getParentNodeInstanceId());
            if (!flow.attemptsFor(parent).stream().filter(item -> item != parent).allMatch(item -> status(item, AttemptStatus.DONE))) {
                return event("APPROVED", attempt, null, task, null);
            }
            stages.set(parent, AttemptStatus.DONE, now);
            completed.add(parent);
        }
        int next = attempt.getStageNo() + 1;
        if (next > flow.children(null).size()) {
            flow.row().setStatus(RunStatus.DONE);
            flow.row().setClosedAt(now);
            log.info("流程运行完成，instanceId={}", flow.row().getId());
            return event("COMPLETED", attempt, null, task, null);
        }
        flow.row().setCurrentStepNo(next);
        var target = stages.activate(flow, flow.stage(next), primaryHandlers);
        return event("ADVANCED", attempt, target, task, null);
    }

    private Map<String, JsonNode> fields(WorkflowRun flow, Node node, Map<String, JsonNode> current,
                                         Map<String, JsonNode> input) {
        var editable = modules.editableFields(flow, node);
        var required = node.requiredFields() == null ? List.<String>of()
                : node.requiredFields().stream().filter(editable::contains).toList();
        var patch = input == null ? Map.<String, JsonNode>of() : input;
        if (!editable.containsAll(patch.keySet())) {
            throw localized("error.workflow.fields");
        }
        var source = flow.source();
        if (source != null && !patch.isEmpty()) {
            patch = modules.require(source.module()).normalizeFields(source, patch);
        }
        for (var field : flow.definition().fields()) {
            if (patch.containsKey(field.code()) || required.contains(field.code())) {
                var options = field.options().stream().map(FieldModels.Option::code).collect(Collectors.toSet());
                validator.value(field.config(), patch.getOrDefault(field.code(), current.get(field.code())), options,
                        required.contains(field.code()));
            }
        }
        return patch;
    }

    /**
     * 驳回到此前已完成的节点：缺省回到上一阶段，指定目标时仅重办该子节点。
     */
    private WorkflowEvent reject(WorkflowRun flow, int actor, Action input, LocalDateTime now) {
        var task = tasks.openTask(flow, actor, input.todoId(), false);
        var attempt = flow.attempt(task.getNodeInstanceId());
        var allowed = flow.rejectTargets();
        if (allowed.isEmpty()) {
            throw conflict(INVALID_STATE);
        }
        String reason = requireReason(input.reason());
        String key = input.targetNodeKey() == null ? flow.stage(flow.row().getCurrentStepNo() - 1).key() : input.targetNodeKey();
        if (!allowed.contains(key)) {
            throw conflict(INVALID_STATE);
        }
        tasks.recordResult(flow, task, "REJECT", input.opinion(), repository.values(flow.row().getFieldValuesJson()), now);
        stages.closeStage(flow, AttemptStatus.RETURNED, actor, reason, now);
        var chosen = flow.node(key);
        var root = chosen.parentKey() == null ? chosen : flow.node(chosen.parentKey());
        flow.row().setCurrentStepNo(root.order());
        var target = stages.activateReturned(flow, root, chosen.parentKey() == null ? null : key);
        var destination = chosen.parentKey() == null ? target : flow.latest(key).orElseThrow();
        log.info("流程驳回，instanceId={}，目标节点={}，操作人 ID={}", flow.row().getId(), key, actor);
        return event("RETURNED", attempt, destination, task, reason);
    }

    /**
     * 收回本人上一步推进：下一阶段尚无人处理时撤回并重新激活原节点。
     */
    private WorkflowEvent recall(WorkflowRun flow, int actor, LocalDateTime now) {
        var advance = runs.recallable(flow, actor).orElseThrow(() -> conflict(INVALID_STATE));
        var original = flow.attempt(advance.getFromNodeInstanceId());
        var next = flow.currentRoot();
        var originalNode = flow.node(original.getNodeKey());
        var root = flow.stage(original.getStageNo());
        var task = flow.tasks().stream().filter(item -> Objects.equals(item.getId(), advance.getTaskId())).findFirst().orElseThrow();
        stages.closeStage(flow, AttemptStatus.WITHDRAWN, actor, "上一步提交已收回", now);
        flow.row().setCurrentStepNo(original.getStageNo());
        var target = stages.activateRecalled(flow, root, originalNode.parentKey() == null ? null : originalNode.key(), task);
        log.info("流程推进收回，instanceId={}，操作人 ID={}", flow.row().getId(), actor);
        return event("RECALLED", next, target, task, null);
    }

    /**
     * 发起人或管理人取消流程，必须填写原因。
     */
    private WorkflowEvent cancel(WorkflowRun flow, int actor, Action input, LocalDateTime now) {
        if (!ownsOrManages(flow, actor)) {
            throw new TodoNotFoundException();
        }
        return stages.cancelStage(flow, actor, requireReason(input.reason()), now);
    }

    /**
     * 上传并登记任务附件，字段须为当前节点可编辑的附件字段。
     */
    Attachment upload(long id, long taskId, String field, int actor, DocumentCenter.Upload input) {
        var flow = runs.visible(id, actor, false);
        attachments.verifyField(flow, tasks.openTask(flow, actor, taskId, true), field);
        return attachments.register(id, taskId, field, actor, attachments.upload(input));
    }

    /**
     * 下载实例可见的已批准附件版本。
     */
    void download(long id, long doc, long version, int actor, DocumentCenter.Download output) {
        attachments.download(runs.visible(id, actor, false), doc, version, output);
    }

    private static String requireReason(String value) {
        if (value == null || value.isBlank()) {
            throw localized("error.workflow.reason");
        }
        return value.strip();
    }
}
