package me.lendev.flow.workbench.flow;

import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO_FLOW_MANAGE;
import static me.lendev.flow.common.exception.BadRequestException.localized;
import static me.lendev.flow.workbench.flow.WorkflowQueries.currentNodes;
import static me.lendev.flow.workbench.flow.WorkflowQueries.instance;
import static me.lendev.flow.workbench.flow.WorkflowRuns.manager;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.workbench.flow.FlowModels.RunStatus;
import me.lendev.flow.workbench.flow.WorkflowDefinitionModels.View;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Instance;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.InstanceFilters;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.InstancePage;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Scope;
import me.lendev.flow.workbench.flow.entity.FlowInstance;
import me.lendev.flow.workbench.flow.entity.WorkflowAttempt;
import me.lendev.flow.workbench.flow.entity.WorkflowTask;
import me.lendev.flow.workbench.todo.TodoAccess;
import me.lendev.flow.workbench.todo.TodoModels.Source;
import me.lendev.flow.workbench.todo.TodoNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分页查询流程实例，批量读取当前页的节点及其处理人。
 */
@Service
@RequiredArgsConstructor
class WorkflowInstanceQueries {

    private final WorkflowRuntimeRepository repository;
    private final WorkflowDefinitionRepository definitions;
    private final WorkflowQueries views;

    /**
     * 按相同的筛选条件统计各标签页，并批量查询当前页的节点和待处理人。
     *
     * @throws me.lendev.flow.common.exception.BadRequestException 发起日期区间倒置
     */
    @Transactional(readOnly = true)
    public InstancePage list(int actor, Scope scope, InstanceFilters filters, int page, int size) {
        if (filters.startedFrom() != null && filters.startedTo() != null && filters.startedFrom().isAfter(filters.startedTo())) {
            throw localized("error.flow.started-range");
        }
        var found = repository.page(actor, manager(), scope, filters, page, size);
        var result = instancePage(found.page());
        return new InstancePage(result.getPageData(), result.getTotal(), found.counts());
    }

    /**
     * 管理人员按模板版本查看实际发起的实例。
     *
     * @throws TodoNotFoundException 模板不存在
     */
    @Transactional(readOnly = true)
    public Page<Instance> byDefinition(long definitionId, int page, int size) {
        TodoAccess.permit(WORKBENCH_TODO_FLOW_MANAGE);
        definitions.find(definitionId, false).orElseThrow(TodoNotFoundException::new);
        return instancePage(repository.byDefinition(definitionId, page, size));
    }

    private Page<Instance> instancePage(Page<FlowInstance> found) {
        var running = found.getPageData().stream().filter(row -> row.getStatus() == RunStatus.RUNNING).toList();
        var nodesByDefinition = definitions.nodesByDefinition(running.stream().map(FlowInstance::getDefinitionId).distinct().toList());
        var roleNames = views.roleNames(nodesByDefinition.values().stream().flatMap(List::stream).toList());
        var work = repository.currentWork(running.stream().map(FlowInstance::getId).toList());
        var workByFlow = work.stream().collect(Collectors.groupingBy(WorkflowAttempt::getFlowInstanceId));
        var tasks = repository.tasks(work.stream().map(WorkflowAttempt::getId).toList());
        var tasksByAttempt = tasks.stream().collect(Collectors.groupingBy(WorkflowTask::getNodeInstanceId));
        var names = views.participantNames(tasks);
        var result = new Page<Instance>();
        result.setTotal(found.getTotal());
        result.setPageData(found.getPageData().stream().map(row -> instance(row, currentNodes(
                workByFlow.getOrDefault(row.getId(), List.of()),
                nodesByDefinition.getOrDefault(row.getDefinitionId(), List.of()), tasksByAttempt, names, roleNames))).toList());
        return result;
    }

    /**
     * 按业务来源分页查询实例摘要，供业务模块对账。
     */
    Page<WorkflowBusinessService.Summary> businessInstances(Source source, int page, int size) {
        var rows = repository.bySource(source, page, size);
        var result = new Page<WorkflowBusinessService.Summary>();
        result.setTotal(rows.getTotal());
        var templates = new HashMap<Long, View>();
        result.setPageData(rows.getPageData().stream().map(row -> summary(row, templates.computeIfAbsent(
                row.getDefinitionId(), id -> definitions.find(id, false).orElseThrow(TodoNotFoundException::new)))).toList());
        return result;
    }

    /**
     * 组装业务侧实例摘要。
     */
    static WorkflowBusinessService.Summary summary(FlowInstance row, View definition) {
        return new WorkflowBusinessService.Summary(row.getId(), definition.id(), definition.code(), definition.revision(),
                row.getTitle(), row.getStatus(), row.getVersion(), row.getStartedAt(), row.getClosedAt());
    }
}
