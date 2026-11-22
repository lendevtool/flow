package me.lendev.flow.workbench.flow;

import static me.lendev.flow.audit.AuditOperation.*;
import static me.lendev.flow.workbench.flow.FlowModels.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditField;
import me.lendev.flow.audit.AuditHandler;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditResultMismatchException;
import me.lendev.flow.audit.AuditSnapshots;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.audit.model.AuditChange;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.workbench.todo.TodoAuditHandler;
import me.lendev.flow.workbench.todo.TodoModels.Todo;
import org.springframework.stereotype.Component;

/**
 * 一个流程审计事件聚合模板或实例变化，以及同事务写入的节点待办变化。
 */
@Component
@RequiredArgsConstructor
class FlowAuditHandler implements AuditHandler {

    private static final String WORKFLOW_DEFINITION = "WORKFLOW_DEFINITION";
    private static final String STATUS = "status";
    private final AuditSnapshots snapshots;
    private final TodoAuditHandler todos;
    private final FlowRepository legacyDefinitions;
    private final WorkflowDefinitionRepository definitions;

    /**
     * 旧版模板变更前后快照，before 为 null 表示新建。
     */
    record DefinitionChange(Definition before, Definition after) {

    }

    /**
     * 节点待办变更前后快照，before 为 null 表示新建。
     */
    record NodeChange(Todo before, Todo after) {

    }

    /**
     * 流程运行变更及同事务的节点待办变化，replay 表示幂等重放。
     */
    record RunChange(Run before, Run after, List<NodeChange> nodes) {

        static RunChange replay(Run row) {
            return new RunChange(row, row, List.of());
        }
    }

    @Override
    public Set<AuditOperation> operations() {
        return Set.of(TODO_FLOW_DEFINE, TODO_FLOW_DEFINE_EDIT, TODO_FLOW_PUBLISH, TODO_FLOW_RETIRE, TODO_FLOW_START,
                TODO_FLOW_COMPLETE, TODO_FLOW_CANCEL, TODO_FLOW_ASSIGN, WORKFLOW_ACTION);
    }

    /**
     * 启动时校验审计入口的返回类型与参数签名，防止审计快照取错结果。
     */
    @Override
    public void validate(Method method) {
        var operation = method.getAnnotation(Audited.class).value();
        List<Class<?>> expected = resultTypes(operation);
        var resultType = method.getReturnType();
        if (!expected.contains(resultType)) {
            throw new IllegalStateException("流程审计入口返回类型不匹配: " + method.toGenericString()
                    + ", 操作=" + operation.code() + ", 预期=" + typeNames(expected));
        }
        if (!isTemplateOperation(operation)) {
            return;
        }
        boolean legacy = resultType == DefinitionChange.class;
        var parameters = List.of(method.getParameterTypes());
        List<Class<?>> expectedParameters = switch (operation) {
            case TODO_FLOW_DEFINE -> List.of(legacy ? CreateDefinition.class : WorkflowDefinitionModels.Write.class);
            case TODO_FLOW_DEFINE_EDIT -> List.of(long.class,
                    legacy ? EditDefinition.class : WorkflowDefinitionModels.Write.class);
            default -> List.of(long.class, legacy ? Version.class : long.class);
        };
        if (!parameters.equals(expectedParameters)) {
            throw new IllegalStateException("流程模板审计入口参数不匹配: " + method.toGenericString()
                    + ", 操作=" + operation.code() + ", 预期=" + typeNames(expectedParameters));
        }
    }

    private static List<Class<?>> resultTypes(AuditOperation operation) {
        return switch (operation) {
            case TODO_FLOW_DEFINE, TODO_FLOW_DEFINE_EDIT, TODO_FLOW_PUBLISH, TODO_FLOW_RETIRE ->
                    List.of(DefinitionChange.class, WorkflowDefinitionModels.Change.class);
            case TODO_FLOW_START ->
                    List.of(RunChange.class, WorkflowRuntimeModels.Detail.class, WorkflowBusinessService.Summary.class);
            case TODO_FLOW_COMPLETE -> List.of(RunChange.class);
            case TODO_FLOW_CANCEL -> List.of(RunChange.class, WorkflowBusinessService.Summary.class);
            case TODO_FLOW_ASSIGN -> List.of(RunChange.class, List.class);
            case WORKFLOW_ACTION -> List.of(WorkflowRuntimeModels.Detail.class);
            default -> throw new IllegalStateException("未配置流程审计操作: " + operation.code());
        };
    }

    private static String typeNames(List<Class<?>> types) {
        return String.join(", ", types.stream().map(Class::getName).toList());
    }

    private static boolean isTemplateOperation(AuditOperation operation) {
        return switch (operation) {
            case TODO_FLOW_DEFINE, TODO_FLOW_DEFINE_EDIT, TODO_FLOW_PUBLISH, TODO_FLOW_RETIRE -> true;
            default -> false;
        };
    }

    /**
     * 运行变更仅在状态实际变化时记录；新版实例入口的变更由聚合审计另行追加。
     */
    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        var expected = resultTypes(operation);
        return new Plan() {
            @Override
            public boolean recordSuccess(Object result) {
                if (expected.stream().noneMatch(type -> type.isInstance(result))) {
                    throw new AuditResultMismatchException(operation, typeNames(expected),
                            result == null ? null : result.getClass());
                }
                return switch (result) {
                    case DefinitionChange ignored -> true;
                    case WorkflowDefinitionModels.Change ignored -> true;
                    case RunChange change -> !change.after().equals(change.before());
                    // 新版实例入口将实际变更追加至同事务的聚合审计。
                    default -> false;
                };
            }

            @Override
            public List<AuditChange> after(Object result) {
                return switch (result) {
                    // 模板写入口在鉴权后锁定目标，并返回用于业务校验的同一份变更前快照。
                    case DefinitionChange change -> List.of(definitionChange(change));
                    case WorkflowDefinitionModels.Change change -> List.of(workflowDefinitionChange(change));
                    case RunChange change -> runChanges(change);
                    case null, default -> throw new AuditResultMismatchException(operation, typeNames(expected),
                            result == null ? null : result.getClass());
                };
            }
        };
    }

    private AuditChange definitionChange(DefinitionChange change) {
        var after = template(legacyDefinitions.definition(change.after().id(), true).orElseThrow());
        return change.before() == null ? snapshots.created(after) : snapshots.updated(template(change.before()), after);
    }

    private AuditChange workflowDefinitionChange(WorkflowDefinitionModels.Change change) {
        var after = workflowTemplate(definitions.find(change.after().id(), true).orElseThrow());
        return change.before() == null ? snapshots.created(after)
                : snapshots.updated(workflowTemplate(change.before()), after);
    }

    private AuditSnapshots.Capture workflowTemplate(WorkflowDefinitionModels.View row) {
        var object = new AuditObject(WORKFLOW_DEFINITION, Long.toString(row.id()), row.code(), row.name(), null, null);
        var frozenFields = row.fields().stream().filter(field -> field.config() != null).toList();
        return snapshots.capture(object, Map.of(
                "code", AuditField.visible("编码", row.code()),
                "name", AuditField.visible("名称", row.name()),
                "revision", AuditField.visible("模板版本", row.revision()),
                STATUS, AuditField.visible("状态", row.status().name()),
                "publishedAt", AuditField.visible("发布时间", row.publishedAt()),
                "steps", AuditField.visible("节点定义", row.nodes()),
                "fields", AuditField.visible("流程字段", row.fields().stream().map(WorkflowDefinitionModels.FieldBinding::code).toList()),
                "fieldRules", AuditField.visible("发布时冻结的字段规则和选项", frozenFields, frozenFields.size() + " 个字段")));
    }

    /**
     * 生成运行主行与节点待办的审计变更，供独立或聚合审计共用。
     */
    List<AuditChange> runChanges(RunChange change) {
        List<AuditChange> results = new ArrayList<>();
        var after = run(change.after());
        results.add(change.before() == null ? snapshots.created(after) : snapshots.updated(run(change.before()), after));
        change.nodes().forEach(node -> results.add(todos.todoChange(node.before(), node.after())));
        return List.copyOf(results);
    }

    private AuditSnapshots.Capture template(Definition row) {
        var object = new AuditObject(WORKFLOW_DEFINITION, Long.toString(row.id()), row.code(), row.name(), null, null);
        return snapshots.capture(object, Map.of(
                "code", AuditField.visible("编码", row.code()),
                "name", AuditField.visible("名称", row.name()),
                "revision", AuditField.visible("模板版本", row.revision()),
                STATUS, AuditField.visible("状态", row.status().name()),
                "steps", AuditField.visible("步骤定义", row.steps())));
    }

    private AuditSnapshots.Capture run(Run row) {
        var object = new AuditObject("WORKFLOW_INSTANCE", Long.toString(row.id()), null, row.title(),
                WORKFLOW_DEFINITION, Long.toString(row.definitionId()));
        Map<String, AuditField> fields = new LinkedHashMap<>();
        fields.put("title", AuditField.visible("标题", row.title()));
        fields.put(STATUS, AuditField.visible("状态", row.status().name()));
        fields.put("ownerUserId", AuditField.visible("发起人", row.ownerUserId(), row.ownerName()));
        fields.put("currentStepNo", AuditField.visible("当前步骤", row.currentStepNo()));
        fields.put("dimensions", AuditField.visible("公共维度", row.dimensions()));
        if (row.source() != null) {
            fields.put("sourceModule", AuditField.visible("来源模块", row.source().module()));
            fields.put("businessType", AuditField.visible("业务类型", row.source().type()));
            fields.put("businessId", AuditField.visible("业务引用", row.source().id()));
        }
        for (Node node : row.steps()) {
            String prefix = "steps." + node.stepNo() + ".";
            fields.put(prefix + "title", AuditField.visible("步骤名称", node.title()));
            fields.put(prefix + STATUS, AuditField.visible("步骤状态", node.status().name()));
            fields.put(prefix + "assigneeUserId", AuditField.visible("步骤负责人", node.assigneeUserId(), node.assigneeName()));
            fields.put(prefix + "todoId", AuditField.visible("待办 ID", node.todoId()));
        }
        return snapshots.capture(object, fields);
    }
}
