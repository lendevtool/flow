package me.lendev.flow.workbench.todo;

import static me.lendev.flow.audit.AuditOperation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditField;
import me.lendev.flow.audit.AuditHandler;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditSnapshots;
import me.lendev.flow.audit.model.AuditChange;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.workbench.todo.TodoModels.Todo;
import org.springframework.stereotype.Component;

/**
 * 工作台变更快照及不含私人内容的标记操作审计。
 */
@Component
@RequiredArgsConstructor
public class TodoAuditHandler implements AuditHandler {

    private final AuditSnapshots snapshots;

    @Override
    public Set<AuditOperation> operations() {
        return Set.of(TODO_CREATE, TODO_EDIT, TODO_START, TODO_COMPLETE, TODO_CANCEL, TODO_ASSIGN,
                TODO_PERSONAL_CONFIGURE, TODO_PERSONAL_TAG);
    }

    /**
     * 仅当写操作实际产生变化时记录审计，幂等命中不产生记录。
     */
    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        return new Plan() {
            @Override
            public boolean recordSuccess(Object result) {
                return switch (result) {
                    case TodoMutations.Change change -> change.changed();
                    case TodoPersonalDimensions.Configuration config -> config.changed();
                    case TodoPersonalDimensions.Tags tags -> !tags.changedSlots().isEmpty();
                    default -> true;
                };
            }

            @Override
            public List<AuditChange> after(Object result) {
                return switch (result) {
                    case TodoMutations.Change change -> List.of(todoChange(change.before(), change.after()));
                    case TodoPersonalDimensions.Configuration config -> List.of(snapshots.reference(
                            new AuditObject("TODO_PERSONAL_DIMENSION", config.userId() + ":" + config.saved().slot(),
                                    null, "个人维度 " + config.saved().slot(), null, null)));
                    case TodoPersonalDimensions.Tags tags -> List.of(personalTagChange(tags, "TODO"));
                    default -> throw new IllegalArgumentException("不支持的待办审计结果");
                };
            }
        };
    }

    /**
     * 记录变更槽位，不记录选项键或私人文本。
     */
    AuditChange personalTagChange(TodoPersonalDimensions.Tags tags, String objectType) {
        return snapshots.reference(new AuditObject(objectType, Long.toString(tags.todoId()), null,
                "个人标记槽位 " + tags.changedSlots(), null, null));
    }

    /**
     * 供流程的外层审计在同一事件中记录受影响的待办。
     */
    public AuditChange todoChange(Todo before, Todo after) {
        var capture = capture(after);
        return before == null ? snapshots.created(capture) : snapshots.updated(capture(before), capture);
    }

    private AuditSnapshots.Capture capture(Todo row) {
        Map<String, AuditField> fields = new LinkedHashMap<>();
        fields.put("title", AuditField.visible("标题", row.title()));
        fields.put("origin", AuditField.visible("来源类型", row.origin().name()));
        fields.put("sourceModule", AuditField.visible("来源模块", row.sourceModule()));
        fields.put("businessId", AuditField.visible("业务引用", row.businessId()));
        fields.put("status", AuditField.visible("状态", row.status().name()));
        fields.put("priority", AuditField.visible("优先级", row.priority().name()));
        fields.put("ownerUserId", AuditField.visible("创建人", row.ownerUserId(), row.ownerName()));
        fields.put("departmentId", AuditField.visible("部门", row.departmentId()));
        fields.put("assigneeUserId", AuditField.visible("负责人", row.assigneeUserId(), row.assigneeName()));
        fields.put("scheduleMode", AuditField.visible("排期方式", row.scheduleMode().name()));
        fields.put("startDate", AuditField.visible("开始日期", row.startDate()));
        fields.put("endDate", AuditField.visible("结束日期", row.endDate()));
        fields.put("dueDate", AuditField.visible("截止日期", row.dueDate()));
        fields.put("marketCode", AuditField.visible("交易市场", row.marketCode()));
        fields.put("tradeBaseDate", AuditField.visible("基准交易日", row.tradeBaseDate()));
        fields.put("startTradeOffset", AuditField.visible("开始偏移", row.startTradeOffset()));
        fields.put("endTradeOffset", AuditField.visible("结束偏移", row.endTradeOffset()));
        fields.put("dueTradeOffset", AuditField.visible("截止偏移", row.dueTradeOffset()));
        fields.put("dimensions", AuditField.visible("公共维度", row.dimensions()));
        return snapshots.capture(new AuditObject("TODO", Long.toString(row.id()), null, row.title(), null, null), fields);
    }
}
