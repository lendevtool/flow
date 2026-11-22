package me.lendev.flow.workbench.flow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import me.lendev.flow.pms.workflownotification.WorkflowNoticeQueue.TaskTrigger;
import me.lendev.flow.workbench.flow.WorkflowDefinitionModels.Node;
import me.lendev.flow.workbench.flow.WorkflowDefinitionModels.View;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.AttemptStatus;
import me.lendev.flow.workbench.flow.entity.FlowInstance;
import me.lendev.flow.workbench.flow.entity.WorkflowAttempt;
import me.lendev.flow.workbench.flow.entity.WorkflowTask;
import me.lendev.flow.workbench.todo.TodoModels.Source;

/**
 * 同一个实例的定义、执行轮次和任务，运行写入由实例行锁串行化。
 */
record WorkflowRun(FlowInstance row, View definition, List<WorkflowAttempt> attempts, List<WorkflowTask> tasks,
                   List<TaskTrigger> taskNoticeMails) {

    WorkflowRun(FlowInstance row, View definition, List<WorkflowAttempt> attempts, List<WorkflowTask> tasks) {
        this(row, definition, attempts, tasks, new ArrayList<>());
    }

    /**
     * 判断节点轮次是否处于指定状态。
     */
    static boolean status(WorkflowAttempt node, AttemptStatus status) {
        return node.getStatus().equals(status.name());
    }

    /**
     * 业务来源标识，工作台发起的实例返回 null。
     */
    Source source() {
        return row.getSourceModule() == null ? null : new Source(
                row.getSourceModule(), row.getBusinessType(), row.getBusinessId(), row.getOwnerUserId());
    }

    /**
     * 实例公共维度槽位取值。
     */
    Map<Integer, Long> dimensions() {
        var result = new LinkedHashMap<Integer, Long>();
        Long[] items = {row.getDimension1ItemId(), row.getDimension2ItemId(), row.getDimension3ItemId(),
                row.getDimension4ItemId(), row.getDimension5ItemId()};
        for (int index = 0; index < items.length; index++) {
            if (items[index] != null) {
                result.put(index + 1, items[index]);
            }
        }
        return result;
    }

    Node node(String key) {
        return definition.nodes().stream().filter(item -> item.key().equals(key)).findFirst().orElseThrow();
    }

    List<String> fieldCodes() {
        return definition.fields().stream().map(WorkflowDefinitionModels.FieldBinding::code).toList();
    }

    /**
     * 模板中指定节点的子节点，按 order 升序；key 为 null 时返回顶层节点。
     */
    List<Node> children(String key) {
        return definition.nodes().stream().filter(item -> Objects.equals(item.parentKey(), key))
                .sorted(Comparator.comparingInt(Node::order)).toList();
    }

    /**
     * 按顺序号定位顶层阶段节点。
     */
    Node stage(int order) {
        return children(null).stream().filter(item -> item.order() == order).findFirst().orElseThrow();
    }

    /**
     * 节点最新一轮尝试。
     */
    Optional<WorkflowAttempt> latest(String key) {
        return attempts.stream().filter(item -> item.getNodeKey().equals(key)).reduce((first, second) -> second);
    }

    /**
     * 当前阶段的最新根节点轮次。
     */
    WorkflowAttempt currentRoot() {
        return latest(stage(row().getCurrentStepNo()).key()).orElseThrow();
    }

    /**
     * 可驳回目标：当前阶段之前已完成的节点编码。
     */
    List<String> rejectTargets() {
        return attempts().stream().filter(attempt -> attempt.getStageNo() < row().getCurrentStepNo()
                && status(attempt, AttemptStatus.DONE)).map(WorkflowAttempt::getNodeKey).distinct().toList();
    }

    WorkflowAttempt attempt(long id) {
        return attempts.stream().filter(item -> item.getId() == id).findFirst().orElseThrow();
    }

    /**
     * 根节点及其直接子节点的轮次集合。
     */
    List<WorkflowAttempt> attemptsFor(WorkflowAttempt root) {
        return attempts.stream().filter(item -> item == root || Objects.equals(item.getParentNodeInstanceId(), root.getId())).toList();
    }

    List<WorkflowTask> tasksFor(long id) {
        return tasks.stream().filter(item -> item.getNodeInstanceId() == id).toList();
    }

    List<String> editable(Node node) {
        return WorkflowDefinitionService.editable(node, fieldCodes(), !children(node.key()).isEmpty());
    }

    /**
     * 可见性：管理人、发起人或任一任务办理人。
     */
    boolean visible(int userId, boolean manage) {
        return manage || Objects.equals(row.getOwnerUserId(), userId)
                || tasks.stream().anyMatch(task -> task.getAssigneeUserId() == userId);
    }
}
