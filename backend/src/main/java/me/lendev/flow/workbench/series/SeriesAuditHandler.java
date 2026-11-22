package me.lendev.flow.workbench.series;

import static me.lendev.flow.audit.AuditField.visible;

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
import me.lendev.flow.audit.AuditSnapshots;
import me.lendev.flow.audit.model.AuditChange;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.workbench.series.SeriesModels.Affected;
import me.lendev.flow.workbench.series.SeriesModels.Rule;
import me.lendev.flow.workbench.series.SeriesService.Mutation;
import org.springframework.stereotype.Component;

/**
 * 保存规则、实例取消明细与每批生成的实例 ID，说明正文不进入快照。
 */
@Component
@RequiredArgsConstructor
class SeriesAuditHandler implements AuditHandler {

    private static final String STATUS = "status";

    private static final String SERIES_TYPE = "TODO_SERIES";
    private final AuditSnapshots snapshots;

    @Override
    public Set<AuditOperation> operations() {
        return Set.of(AuditOperation.TODO_SERIES_CREATE, AuditOperation.TODO_SERIES_EDIT,
                AuditOperation.TODO_SERIES_STOP, AuditOperation.TODO_SERIES_GENERATE);
    }

    /**
     * 审计方法必须返回 {@link Mutation}，以便读取实际变更结果。
     */
    @Override
    public void validate(Method method) {
        if (method.getReturnType() != Mutation.class) {
            throw new IllegalStateException("周期待办审计需要实际变更结果: " + method.getName());
        }
    }

    /**
     * 仅当规则版本推进或有实例生成、取消时记录审计，幂等调用不产生记录。
     */
    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        return new Plan() {
            @Override
            public boolean recordSuccess(Object result) {
                var change = (Mutation) result;
                return change.after() != null && (change.before() == null
                        || change.before().version() != change.after().version()
                        || !change.generated().isEmpty() || !change.cancelled().isEmpty());
            }

            @Override
            public List<AuditChange> after(Object result) {
                return changes((Mutation) result, operation);
            }
        };
    }

    private List<AuditChange> changes(Mutation mutation, AuditOperation operation) {
        Rule after = mutation.after();
        if (after == null) {
            return List.of();
        }
        var object = new AuditObject(SERIES_TYPE, Long.toString(after.id()), null, after.rule().title(), null, null);
        if (mutation.before() == after) {
            return List.of(snapshots.reference(object));
        }
        List<AuditChange> changes = new ArrayList<>();
        List<Long> generated = mutation.generated();
        if (mutation.before() == null) {
            changes.add(snapshots.created(snapshot(after, object, generated)));
        } else {
            changes.add(snapshots.updated(snapshot(mutation.before(), object, List.of()),
                    snapshot(after, object, generated)));
        }
        String kind = operation == AuditOperation.TODO_SERIES_STOP ? "SERIES_STOP" : "SERIES_CHANGE";
        mutation.cancelled().forEach(todo -> changes.add(cancelled(object, todo, kind)));
        return List.copyOf(changes);
    }

    private AuditSnapshots.Capture snapshot(Rule rule, AuditObject object, List<Long> generated) {
        var definition = rule.rule();
        Map<String, AuditField> values = new LinkedHashMap<>();
        values.put("title", visible("标题", definition.title()));
        values.put("ownerUserId", visible("发起人", rule.ownerUserId()));
        values.put("assigneeUserId", visible("负责人", definition.assigneeUserId()));
        values.put("priority", visible("优先级", definition.priority()));
        values.put("frequency", visible("频率", definition.frequency()));
        values.put("intervalValue", visible("间隔", definition.intervalValue()));
        values.put("weekdaysMask", visible("选中的星期", definition.weekdaysMask()));
        values.put("monthDayMode", visible("月内日期模式", definition.monthDayMode()));
        values.put("anchorDate", visible("首个锚点", definition.anchorDate()));
        values.put("untilDate", visible("最后允许的锚点", definition.untilDate()));
        values.put("durationDays", visible("计划天数", definition.durationDays()));
        values.put("dueOffsetDays", visible("自然日截止偏移", definition.dueOffsetDays()));
        values.put("marketCode", visible("市场", definition.marketCode()));
        values.put("startTradeOffset", visible("开始交易日偏移", definition.startTradeOffset()));
        values.put("endTradeOffset", visible("结束交易日偏移", definition.endTradeOffset()));
        values.put("dueTradeOffset", visible("截止交易日偏移", definition.dueTradeOffset()));
        for (int slot = 1; slot <= 5; slot++) {
            values.put("dimension" + slot, visible("公共维度 " + slot, definition.dimensions().get(slot)));
        }
        values.put(STATUS, visible("状态", rule.status()));
        values.put("ruleRevision", visible("规则版本", rule.ruleRevision()));
        values.put("effectiveFromDate", visible("生效业务日期", rule.effectiveFromDate()));
        values.put("lastBaseDate", visible("上次处理锚点", rule.lastBaseDate()));
        values.put("nextBaseDate", visible("下次锚点", rule.nextBaseDate()));
        values.put("blockedDate", visible("阻塞日期", rule.blockedDate()));
        values.put("blockReason", visible("阻塞原因", rule.blockReason()));
        values.put("generatedTodoIds", visible("生成实例 ID", generated));
        return snapshots.capture(object, values);
    }

    private AuditChange cancelled(AuditObject rule, Affected affected, String kind) {
        var todo = new AuditObject("TODO", Long.toString(affected.id()), null, affected.title(),
                rule.objectType(), rule.objectId());
        var before = snapshots.capture(todo, Map.of(STATUS, visible("状态", "OPEN"),
                "cancelKind", visible("取消类型", null), "version", visible("版本", affected.version())));
        var after = snapshots.capture(todo, Map.of(STATUS, visible("状态", "CANCELLED"),
                "cancelKind", visible("取消类型", kind), "version", visible("版本", affected.version() + 1)));
        return snapshots.updated(before, after);
    }
}
