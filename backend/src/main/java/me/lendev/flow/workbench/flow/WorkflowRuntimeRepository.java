package me.lendev.flow.workbench.flow;

import static me.lendev.flow.workbench.flow.entity.table.FlowDefinitionTableDef.FLOW_DEFINITION;
import static me.lendev.flow.workbench.flow.entity.table.FlowInstanceTableDef.FLOW_INSTANCE;
import static me.lendev.flow.workbench.flow.entity.table.WorkflowAttemptTableDef.WORKFLOW_ATTEMPT;
import static me.lendev.flow.workbench.flow.entity.table.WorkflowEventTableDef.WORKFLOW_EVENT;
import static me.lendev.flow.workbench.flow.entity.table.WorkflowReuseTableDef.WORKFLOW_REUSE;
import static me.lendev.flow.workbench.flow.entity.table.WorkflowTaskTableDef.WORKFLOW_TASK;
import static me.lendev.flow.workbench.flow.entity.table.WorkflowUploadTableDef.WORKFLOW_UPLOAD;
import static me.lendev.flow.workbench.todo.entity.table.TodoInstanceTableDef.TODO_INSTANCE;

import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.InstanceFilters;
import me.lendev.flow.workbench.flow.WorkflowRuntimeModels.Scope;
import me.lendev.flow.workbench.flow.entity.*;
import me.lendev.flow.workbench.flow.mapper.*;
import me.lendev.flow.workbench.todo.TodoModels.Status;
import me.lendev.flow.workbench.todo.TodoModels.Source;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 流程实例锁、节点轮次、统一待办关联和历史的持久化接口。
 */
@Repository
@RequiredArgsConstructor
class WorkflowRuntimeRepository {

    private static final TypeReference<Map<String, JsonNode>> VALUES = new TypeReference<>() {
    };
    private final FlowInstanceMapper instances;
    private final WorkflowAttemptMapper attempts;
    private final WorkflowTaskMapper tasks;
    private final WorkflowReuseMapper reuse;
    private final WorkflowEventMapper events;
    private final WorkflowUploadMapper uploads;
    private final ObjectMapper json;

    /**
     * 读取新版实例，lock 为 true 时锁定实例主行。
     */
    Optional<FlowInstance> find(long id, boolean lock) {
        var query = runtime().and(FLOW_INSTANCE.ID.eq(id));
        return Optional.ofNullable(instances.selectOneByQuery(lock ? query.forUpdate() : query));
    }

    /**
     * 按现有全部实例范围的可见性规则检查某个新版实例，无需加载运行数据。
     */
    boolean visible(long id, int viewer, boolean manager) {
        return instances.selectCountByQuery(scoped(runtime().and(FLOW_INSTANCE.ID.eq(id)), viewer, manager, Scope.ALL)) > 0;
    }

    /**
     * 按幂等作用域与请求键查询实例，不加锁。
     */
    Optional<FlowInstance> findRequest(String scope, String key) {
        return Optional.ofNullable(instances.selectOneByQuery(runtime()
                .and(FLOW_INSTANCE.REQUEST_SCOPE.eq(scope, true)).and(FLOW_INSTANCE.REQUEST_KEY.eq(key, true))));
    }

    /**
     * 业务发起路径的幂等查询，加行锁串行化同键并发。
     */
    Optional<FlowInstance> findBusinessRequest(String scope, String key) {
        return Optional.ofNullable(instances.selectOneByQuery(runtime()
                .and(FLOW_INSTANCE.REQUEST_SCOPE.eq(scope, true)).and(FLOW_INSTANCE.REQUEST_KEY.eq(key, true)).forUpdate()));
    }

    /**
     * 按业务来源三元组分页查询实例，按 ID 倒序。
     */
    Page<FlowInstance> bySource(Source source, int page, int size) {
        return Page.convert(instances.paginate(page, size, runtime()
                .and(FLOW_INSTANCE.SOURCE_MODULE.eq(source.module(), true))
                .and(FLOW_INSTANCE.BUSINESS_TYPE.eq(source.type(), true))
                .and(FLOW_INSTANCE.BUSINESS_ID.eq(source.id(), true)).orderBy(FLOW_INSTANCE.ID.desc())));
    }

    /**
     * 按模板版本分页查询实例。
     */
    Page<FlowInstance> byDefinition(long definitionId, int page, int size) {
        return Page.convert(instances.paginate(page, size, QueryWrapper.create().where(FLOW_INSTANCE.DEFINITION_ID.eq(definitionId))
                .orderBy(FLOW_INSTANCE.ID.desc())));
    }

    /**
     * 当前范围分页结果及全部范围的计数。
     */
    record ScopedPage(Page<FlowInstance> page, Map<Scope, Long> counts) {

    }

    /**
     * 按同一筛选条件分页查询并统计各范围数量。
     */
    ScopedPage page(int viewer, boolean manager, Scope scope, InstanceFilters filters, int page, int size) {
        var filtered = filteredInstances(filters);
        var result = Page.convert(instances.paginate(page, size,
                scoped(filtered.clone(), viewer, manager, scope).orderBy(FLOW_INSTANCE.ID.desc())));
        var counts = new EnumMap<Scope, Long>(Scope.class);
        for (Scope candidate : Scope.values()) {
            counts.put(candidate, candidate == scope ? result.getTotal()
                    : instances.selectCountByQuery(scoped(filtered.clone(), viewer, manager, candidate)));
        }
        return new ScopedPage(result, counts);
    }

    private static QueryWrapper filteredInstances(InstanceFilters filters) {
        var query = runtime();
        if (StringUtils.hasText(filters.title())) {
            query.and(FLOW_INSTANCE.TITLE.like(filters.title().strip()));
        }
        if (StringUtils.hasText(filters.ownerName())) {
            query.and(FLOW_INSTANCE.OWNER_NAME.like(filters.ownerName().strip()));
        }
        if (filters.startedFrom() != null) {
            query.and(FLOW_INSTANCE.STARTED_AT.ge(filters.startedFrom().atStartOfDay()));
        }
        if (filters.startedTo() != null && !filters.startedTo().equals(LocalDate.MAX)) {
            query.and(FLOW_INSTANCE.STARTED_AT.lt(filters.startedTo().plusDays(1).atStartOfDay()));
        }
        return query;
    }

    private static QueryWrapper scoped(QueryWrapper query, int viewer, boolean manager, Scope scope) {
        var owned = FLOW_INSTANCE.OWNER_USER_ID.eq(viewer);
        switch (scope) {
            case MINE -> query.and(byTask(WORKFLOW_TASK.ASSIGNEE_USER_ID.eq(viewer)
                    .and(TODO_INSTANCE.STATUS.in(Status.OPEN, Status.IN_PROGRESS)), true));
            case STARTED -> query.and(owned);
            case APPROVED -> query.and(byTask(WORKFLOW_TASK.PROCESSED_BY.eq(viewer), false));
            case DONE, ALL -> {
                if (!manager) {
                    query.and(owned.or(byTask(WORKFLOW_TASK.ASSIGNEE_USER_ID.eq(viewer), false)));
                }
                if (scope == Scope.DONE) {
                    query.and(FLOW_INSTANCE.STATUS.eq(FlowModels.RunStatus.DONE));
                }
            }
        }
        return query;
    }

    private static QueryCondition byTask(QueryCondition filter, boolean openTodo) {
        var query = QueryWrapper.create().select(WORKFLOW_ATTEMPT.FLOW_INSTANCE_ID).from(WORKFLOW_ATTEMPT)
                .join(WORKFLOW_TASK).on(WORKFLOW_TASK.NODE_INSTANCE_ID.eq(WORKFLOW_ATTEMPT.ID));
        if (openTodo) {
            query.join(TODO_INSTANCE).on(TODO_INSTANCE.ID.eq(WORKFLOW_TASK.TODO_ID));
        }
        return FLOW_INSTANCE.ID.in(query.where(filter));
    }

    private static QueryWrapper runtime() {
        return QueryWrapper.create().where(FLOW_INSTANCE.DEFINITION_ID.in(QueryWrapper.create().select(FLOW_DEFINITION.ID)
                .from(FLOW_DEFINITION).where(FLOW_DEFINITION.SCHEMA_VERSION.eq(WorkflowDefinitionRepository.SCHEMA_VERSION))));
    }

    /**
     * 插入实例主行，回填自增 ID。
     */
    void insert(FlowInstance row) {
        instances.insertSelective(row);
    }

    /**
     * 全量覆盖实例主行，调用方须已持有实例锁并递增版本。
     */
    void update(FlowInstance row) {
        instances.update(row, false);
    }

    void insert(WorkflowAttempt row) {
        attempts.insertSelective(row);
    }

    void update(WorkflowAttempt row) {
        attempts.update(row, false);
    }

    void insert(WorkflowTask row) {
        tasks.insertSelective(row);
    }

    void update(WorkflowTask row) {
        tasks.update(row, false);
    }

    void insert(WorkflowEvent row) {
        events.insertSelective(row);
    }

    void insert(WorkflowUpload row) {
        uploads.insertSelective(row);
    }

    void update(WorkflowUpload row) {
        uploads.update(row, false);
    }

    /**
     * 按 ID 升序查询实例的全部节点轮次。
     */
    List<WorkflowAttempt> attempts(long flowId) {
        return attempts.selectListByQuery(QueryWrapper.create().where(WORKFLOW_ATTEMPT.FLOW_INSTANCE_ID.eq(flowId))
                .orderBy(WORKFLOW_ATTEMPT.ID.asc()));
    }

    /**
     * 批量查询实例当前阶段办理中或待分派的节点。
     */
    List<WorkflowAttempt> currentWork(List<Long> flowIds) {
        if (flowIds.isEmpty()) {
            return List.of();
        }
        return attempts.selectListByQuery(QueryWrapper.create().select(WORKFLOW_ATTEMPT.ALL_COLUMNS)
                .from(WORKFLOW_ATTEMPT).join(FLOW_INSTANCE).on(WORKFLOW_ATTEMPT.FLOW_INSTANCE_ID.eq(FLOW_INSTANCE.ID)
                        .and(WORKFLOW_ATTEMPT.STAGE_NO.eq(FLOW_INSTANCE.CURRENT_STEP_NO)))
                .where(WORKFLOW_ATTEMPT.FLOW_INSTANCE_ID.in(flowIds)).and(WORKFLOW_ATTEMPT.MODE.isNotNull())
                .and(WORKFLOW_ATTEMPT.STATUS.in(WorkflowRuntimeModels.AttemptStatus.ACTIVE.name(),
                        WorkflowRuntimeModels.AttemptStatus.WAITING_ASSIGNMENT.name()))
                .orderBy(WORKFLOW_ATTEMPT.ID.asc()));
    }

    /**
     * 批量查询节点轮次的任务，空集合返回空列表。
     */
    List<WorkflowTask> tasks(List<Long> ids) {
        return ids.isEmpty() ? List.of() : tasks.selectListByQuery(QueryWrapper.create()
                .where(WORKFLOW_TASK.NODE_INSTANCE_ID.in(ids)).orderBy(WORKFLOW_TASK.ID.asc()));
    }

    /**
     * 记录驳回重办时的子节点复用链接。
     */
    void reuse(long parentId, long childId) {
        var link = new WorkflowReuse();
        link.setParentNodeInstanceId(parentId);
        link.setChildNodeInstanceId(childId);
        reuse.insert(link);
    }

    /**
     * 查询父节点复用过的子节点轮次 ID。
     */
    List<Long> reused(long parentId) {
        return reuse.selectListByQuery(QueryWrapper.create().where(WORKFLOW_REUSE.PARENT_NODE_INSTANCE_ID.eq(parentId)))
                .stream().map(WorkflowReuse::getChildNodeInstanceId).toList();
    }

    /**
     * 按统一待办 ID 定位流程任务。
     */
    Optional<WorkflowTask> byTodo(long todoId) {
        return Optional.ofNullable(tasks.selectOneByQuery(QueryWrapper.create().where(WORKFLOW_TASK.TODO_ID.eq(todoId))));
    }

    /**
     * 按流程任务主键定位任务。
     */
    Optional<WorkflowTask> task(long taskId) {
        return Optional.ofNullable(tasks.selectOneById(taskId));
    }

    /**
     * 查询任务所属的实例 ID。
     */
    long flowId(WorkflowTask task) {
        return attempts.selectOneById(task.getNodeInstanceId()).getFlowInstanceId();
    }

    /**
     * 查询用户名下待办仍开放的主办任务，用于委托补发候选。
     */
    List<WorkflowTask> openPrimaryTasks(int owner) {
        var open = QueryWrapper.create().select(TODO_INSTANCE.ID).from(TODO_INSTANCE)
                .where(TODO_INSTANCE.STATUS.in(Status.OPEN, Status.IN_PROGRESS));
        return tasks.selectListByQuery(QueryWrapper.create().where(WORKFLOW_TASK.ASSIGNEE_USER_ID.eq(owner))
                .and(WORKFLOW_TASK.IDENTITY.eq("PRIMARY")).and(WORKFLOW_TASK.TODO_ID.in(open)));
    }

    /**
     * 查询委托已补发的任务，按 ID 倒序。
     */
    List<WorkflowTask> forwarded(long delegationId) {
        return tasks.selectListByQuery(QueryWrapper.create().where(WORKFLOW_TASK.DELEGATION_ID.eq(delegationId))
                .orderBy(WORKFLOW_TASK.ID.desc()));
    }

    /**
     * 按实例与请求键查询事件，用于动作幂等。
     */
    Optional<WorkflowEvent> request(long flowId, String key) {
        return Optional.ofNullable(events.selectOneByQuery(QueryWrapper.create()
                .where(WORKFLOW_EVENT.FLOW_INSTANCE_ID.eq(flowId)).and(WORKFLOW_EVENT.REQUEST_KEY.eq(key, true))));
    }

    /**
     * 查询推进到指定节点的最近 ADVANCED 事件，用于收回判定。
     */
    Optional<WorkflowEvent> advancement(long flowId, long nextAttemptId) {
        return Optional.ofNullable(events.selectOneByQuery(QueryWrapper.create().where(WORKFLOW_EVENT.FLOW_INSTANCE_ID.eq(flowId))
                .and(WORKFLOW_EVENT.TO_NODE_INSTANCE_ID.eq(nextAttemptId)).and(WORKFLOW_EVENT.EVENT_TYPE.eq("ADVANCED"))
                .orderBy(WORKFLOW_EVENT.ID.desc()).limit(1)));
    }

    /**
     * 查询节点曾发生的 RECALLED 事件，用于重试分派时恢复原办理人。
     */
    Optional<WorkflowEvent> recallFor(long flowId, long attemptId) {
        return Optional.ofNullable(events.selectOneByQuery(QueryWrapper.create()
                .where(WORKFLOW_EVENT.FLOW_INSTANCE_ID.eq(flowId)).and(WORKFLOW_EVENT.TO_NODE_INSTANCE_ID.eq(attemptId))
                .and(WORKFLOW_EVENT.EVENT_TYPE.eq("RECALLED")).limit(1)));
    }

    /**
     * 分页查询事件历史，按时间与 ID 倒序。
     */
    Page<WorkflowEvent> history(long flowId, int page, int size) {
        return Page.convert(events.paginate(page, size, QueryWrapper.create().where(WORKFLOW_EVENT.FLOW_INSTANCE_ID.eq(flowId))
                .orderBy(WORKFLOW_EVENT.CREATED_AT.desc(), WORKFLOW_EVENT.ID.desc())));
    }

    /**
     * 按实例与文档版本查询上传登记。
     */
    Optional<WorkflowUpload> upload(long flowId, long versionId) {
        return Optional.ofNullable(uploads.selectOneByQuery(QueryWrapper.create()
                .where(WORKFLOW_UPLOAD.FLOW_INSTANCE_ID.eq(flowId)).and(WORKFLOW_UPLOAD.VERSION_ID.eq(versionId))));
    }

    /**
     * 解析字段值 JSON，空输入返回空 Map。
     */
    Map<String, JsonNode> values(String raw) {
        return raw == null ? Map.of() : json.readValue(raw, VALUES);
    }

    /**
     * 序列化为 JSON 字符串。
     */
    String json(Object value) {
        return json.writeValueAsString(value);
    }
}
