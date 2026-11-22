package me.lendev.flow.workbench.flow;

import static me.lendev.flow.workbench.flow.WorkflowRun.status;
import static me.lendev.flow.workbench.flow.WorkflowRuns.manager;
import static me.lendev.flow.workbench.flow.WorkflowRuns.ownsOrManages;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.workbench.flow.FlowModels.RunStatus;
import me.lendev.flow.workbench.flow.WorkflowDefinitionModels.Mode;
import me.lendev.flow.workbench.flow.WorkflowDefinitionModels.Node;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.AttemptStatus;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.CurrentNode;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Detail;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Dispatch;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.History;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Identity;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Instance;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.NodeView;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Receiver;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.TaskView;
import me.lendev.flow.workbench.flow.entity.FlowInstance;
import me.lendev.flow.workbench.flow.entity.WorkflowAttempt;
import me.lendev.flow.workbench.flow.entity.WorkflowEvent;
import me.lendev.flow.workbench.flow.entity.WorkflowTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 查询流程实例、历史和待处理人，并根据当前用户权限组装展示结果。
 */
@Service
@RequiredArgsConstructor
class WorkflowQueries {

    private final WorkflowRuntimeRepository repository;
    private final WorkflowPeople people;
    private final WorkflowRuns runs;
    private final WorkflowStages stages;
    private final WorkflowBusinessRegistry modules;
    private final WorkflowAttachments attachments;

    /**
     * 查询实例详情，按当前用户权限裁剪任务与操作能力。
     *
     * @throws TodoNotFoundException 实例不存在或当前人不可见
     */
    @Transactional(readOnly = true)
    public Detail detail(long id, int actor) {
        return detail(runs.visible(id, actor, false), actor);
    }

    /**
     * 分页查询事件历史，附带处理意见与附件快照。
     */
    @Transactional(readOnly = true)
    public Page<History> history(long id, int actor, int page, int size) {
        var flow = runs.visible(id, actor, false);
        var found = repository.history(id, page, size);
        var result = new Page<History>();
        result.setTotal(found.getTotal());
        result.setPageData(found.getPageData().stream().map(event -> history(flow, event)).toList());
        return result;
    }

    private History history(WorkflowRun flow, WorkflowEvent event) {
        var task = flow.tasks().stream().filter(item -> Objects.equals(item.getId(), event.getTaskId())).findFirst().orElse(null);
        var snapshot = task == null ? Map.<String, JsonNode>of() : repository.values(task.getAttachmentSnapshotJson());
        String from = event.getFromNodeInstanceId() == null ? null : flow.attempt(event.getFromNodeInstanceId()).getNodeKey();
        String to = event.getToNodeInstanceId() == null ? null : flow.attempt(event.getToNodeInstanceId()).getNodeKey();
        return new History(event.getId(), event.getEventType(), from, to, event.getActorUserId(),
                people.name(event.getActorUserId()), task == null ? null : task.getIdentity(), event.getReason(),
                task == null ? null : task.getOpinion(), event.getCreatedAt(), snapshot, attachments.names(flow.row().getId(), snapshot));
    }

    /**
     * 组装已加载实例的详情视图，供写操作返回最新状态复用。
     */
    Detail detail(WorkflowRun flow, int actor) {
        var row = flow.row();
        var values = repository.values(row.getFieldValuesJson());
        boolean running = row.getStatus() == RunStatus.RUNNING;
        boolean mayHandle = flow.tasks().stream().anyMatch(task -> task.getAssigneeUserId() == actor) && runs.mayHandle(flow, actor);
        var tasks = flow.tasks().stream().filter(task -> task.getAssigneeUserId() == actor)
                .map(task -> {
                    var attempt = flow.attempt(task.getNodeInstanceId());
                    var node = flow.node(attempt.getNodeKey());
                    var canSubmit = running && mayHandle && task.getProcessedAt() == null && status(attempt, AttemptStatus.ACTIVE);
                    List<String> editable = canSubmit ? modules.editableFields(flow, node) : List.of();
                    var required = node.requiredFields() == null ? List.<String>of()
                            : node.requiredFields().stream().filter(editable::contains).toList();
                    return new TaskView(task.getId(), task.getTodoId(), node.key(), Identity.valueOf(task.getIdentity()),
                            editable, required,
                            canSubmit, canSubmit ? flow.rejectTargets() : List.of());
                }).toList();
        var tasksByAttempt = flow.tasks().stream().collect(Collectors.groupingBy(WorkflowTask::getNodeInstanceId));
        var names = participantNames(flow.tasks());
        var roleNames = roleNames(flow.definition().nodes());
        var current = running ? currentNodes(flow.attemptsFor(flow.currentRoot()), flow.definition().nodes(),
                tasksByAttempt, names, roleNames) : List.<CurrentNode>of();
        return new Detail(instance(row, current), flow.definition(), values, flow.attempts().stream()
                .map(item -> new NodeView(item.getId(), item.getNodeKey(), item.getStageNo(), item.getAttemptNo(),
                        item.getStatus(), roleName(item, flow.node(item.getNodeKey()), roleNames),
                        processedNames(tasksByAttempt.getOrDefault(item.getId(), List.of()), names),
                        assigneeNames(item, tasksByAttempt.getOrDefault(item.getId(), List.of()), names))).toList(),
                tasks, runs.recallable(flow, actor).isPresent(),
                running && ownsOrManages(flow, actor),
                running && manager() && status(flow.currentRoot(), AttemptStatus.WAITING_ASSIGNMENT),
                attachments.names(row.getId(), attachments.snapshot(flow, values)), sourceUrl(flow, actor),
                roleNames, running ? plannedAssignees(flow) : Map.of());
    }

    /**
     * 预览尚未到达的节点。实际派发仍以节点激活时的角色和休假配置为准。
     */
    private Map<String, List<String>> plannedAssignees(WorkflowRun flow) {
        record Assignment(List<String> roleCodes, Mode mode) {

        }
        var now = people.now();
        var byAssignment = new HashMap<Assignment, Dispatch>();
        var plans = new LinkedHashMap<String, Dispatch>();
        for (var stage : flow.children(null)) {
            if (stage.order() <= flow.row().getCurrentStepNo()) {
                continue;
            }
            var nodes = stage.mode() == null ? flow.children(stage.key()) : List.of(stage);
            for (var node : nodes) {
                plans.put(node.key(), byAssignment.computeIfAbsent(new Assignment(node.handlingRoles(), node.mode()),
                        key -> stages.dispatchPlan(flow, node, now)));
            }
        }
        var names = people.names(plans.values().stream().flatMap(plan -> plan.receivers().stream())
                .map(Receiver::userId).collect(Collectors.toSet()));
        return plans.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue()
                .receivers().stream().map(receiver -> names.getOrDefault(receiver.userId(), "#" + receiver.userId())).toList()));
    }

    /**
     * 批量查询任务办理人与处理人的展示名。
     */
    Map<Integer, String> participantNames(List<WorkflowTask> tasks) {
        var ids = tasks.stream().flatMap(task -> Stream.of(task.getAssigneeUserId(), task.getProcessedBy()))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        return people.names(ids);
    }

    /**
     * 组装办理中或待分派节点的摘要视图。
     */
    static List<CurrentNode> currentNodes(List<WorkflowAttempt> attempts, List<Node> nodes,
                                          Map<Long, List<WorkflowTask>> tasks, Map<Integer, String> names,
                                          Map<String, String> roleNames) {
        var definitions = nodes.stream().collect(Collectors.toMap(Node::key, node -> node));
        return attempts.stream().filter(item -> item.getMode() != null
                        && (status(item, AttemptStatus.ACTIVE) || status(item, AttemptStatus.WAITING_ASSIGNMENT)))
                .map(item -> {
                    var node = definitions.get(item.getNodeKey());
                    return new CurrentNode(item.getNodeKey(), node == null ? item.getNodeKey() : node.title(),
                            item.getStatus(), roleName(item, node, roleNames),
                            assigneeNames(item, tasks.getOrDefault(item.getId(), List.of()), names));
                }).toList();
    }

    /**
     * 批量查询节点涉及角色的名称。
     */
    Map<String, String> roleNames(List<Node> nodes) {
        return people.roleNames(nodes.stream().flatMap(node -> node.handlingRoles().stream()).distinct().toList());
    }

    private static String roleName(WorkflowAttempt attempt, Node node, Map<String, String> roleNames) {
        if (node != null && node.mode() == Mode.MULTI_ALL) {
            return node.handlingRoles().stream().map(code -> roleNames.getOrDefault(code, code))
                    .collect(Collectors.joining("、"));
        }
        return attempt.getRoleName();
    }

    private static List<String> processedNames(List<WorkflowTask> tasks, Map<Integer, String> names) {
        return tasks.stream().map(WorkflowTask::getProcessedBy).filter(Objects::nonNull).distinct()
                .map(id -> names.getOrDefault(id, "#" + id)).toList();
    }

    private static List<String> assigneeNames(WorkflowAttempt attempt, List<WorkflowTask> tasks, Map<Integer, String> names) {
        if (!status(attempt, AttemptStatus.ACTIVE)) {
            return List.of();
        }
        return tasks.stream().filter(task -> task.getProcessedAt() == null).map(WorkflowTask::getAssigneeUserId)
                .distinct().map(id -> names.getOrDefault(id, "#" + id)).toList();
    }

    private String sourceUrl(WorkflowRun flow, int actor) {
        var source = flow.source();
        if (source == null) {
            return null;
        }
        String path = modules.require(source.module()).openPath(source, actor);
        return path != null && path.startsWith("/") && !path.startsWith("//") && path.indexOf((char) 92) < 0
                && path.codePoints().noneMatch(Character::isISOControl) ? path : null;
    }

    /**
     * 组装实例摘要视图。
     */
    static Instance instance(FlowInstance row, List<CurrentNode> current) {
        return new Instance(row.getId(), row.getDefinitionId(), row.getTitle(), row.getOwnerUserId(), row.getOwnerName(),
                row.getCurrentStepNo(), row.getStatus(), row.getVersion(), row.getStartedAt(), row.getClosedAt(), current);
    }
}
