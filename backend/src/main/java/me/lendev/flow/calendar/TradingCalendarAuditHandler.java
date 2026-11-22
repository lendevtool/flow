package me.lendev.flow.calendar;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.AuditField;
import me.lendev.flow.audit.AuditHandler;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditSnapshots;
import me.lendev.flow.audit.model.AuditChange;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.calendar.TradingCalendarRepository.Delta;
import me.lendev.flow.calendar.TradingCalendarRepository.Values;
import org.springframework.stereotype.Component;

/**
 * 根据已提交写入计划记录实际范围、市场及计数；仅单日变更记录字段前后值。
 */
@Component
@RequiredArgsConstructor
class TradingCalendarAuditHandler implements AuditHandler {

    private static final String TYPE = "TRADING_CALENDAR";
    private final AuditSnapshots snapshots;

    @Override
    public Set<AuditOperation> operations() {
        return Set.of(AuditOperation.TRADING_CALENDAR_INITIALIZE, AuditOperation.TRADING_CALENDAR_BATCH,
                AuditOperation.TRADING_CALENDAR_IMPORT);
    }

    @Override
    public void validate(Method method) {
        if (method.getReturnType() != TradingCalendarPlan.class) {
            throw new IllegalStateException("交易日历审计需要实际写入计划: " + method.getName());
        }
    }

    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        return result -> List.of(capture((TradingCalendarPlan) result));
    }

    private AuditChange capture(TradingCalendarPlan plan) {
        var applied = plan.deltas().stream().filter(Delta::modified).toList();
        if (applied.isEmpty()) {
            return snapshots.reference(object(plan, plan.deltas()));
        }
        AuditObject object = object(plan, applied);
        var counts = plan.preview();
        var before = counts(0, 0, counts.unchanged());
        var after = counts(counts.added(), counts.changed(), counts.unchanged());
        after.put("marketCodes", AuditField.visible("受影响市场",
                applied.stream().map(delta -> delta.market().market().code()).distinct().toList()));
        after.put("startDate", AuditField.visible("受影响起始日期", dateBound(applied, false)));
        after.put("endDate", AuditField.visible("受影响结束日期", dateBound(applied, true)));
        if (applied.size() == 1) {
            Delta delta = applied.getFirst();
            if (delta.before() != null) {
                values(before, delta.before().values());
            }
            values(after, delta.after());
        }
        // 字段在旧侧不存在，但范围和市场仍是可显示的审计信息。
        after.forEach((path, value) -> {
            if (!before.containsKey(path)) {
                String label = switch (path) {
                    case "marketCodes" -> "受影响市场";
                    case "startDate" -> "受影响起始日期";
                    case "endDate" -> "受影响结束日期";
                    case "isTradingDay" -> "是否交易日";
                    case "holidayName" -> "假期名称";
                    case "source" -> "来源";
                    default -> "备注";
                };
                before.put(path, AuditField.missing(label, true));
            }
        });
        return snapshots.updated(snapshots.capture(object, before), snapshots.capture(object, after));
    }

    private static AuditObject object(TradingCalendarPlan plan, List<Delta> deltas) {
        if (deltas.isEmpty()) {
            return new AuditObject(TYPE, TYPE, null, "交易日历", null, null);
        }
        var codes = deltas.stream().map(delta -> delta.market().market().code()).distinct()
                .collect(Collectors.joining(", "));
        if (codes.length() > 100) {
            codes = codes.substring(0, 100) + "…";
        }
        String from = dateBound(deltas, false);
        String to = dateBound(deltas, true);
        String dates = from.equals(to) ? from : from + " ~ " + to;
        String name = "%s %s（新增 %d，修改 %d，未变更 %d）".formatted(codes, dates,
                plan.preview().added(), plan.preview().changed(), plan.preview().unchanged());
        return new AuditObject(TYPE, TYPE, null, name, null, null);
    }

    private static String dateBound(List<Delta> deltas, boolean latest) {
        Comparator<LocalDate> order = latest ? Comparator.reverseOrder() : Comparator.naturalOrder();
        return deltas.stream().map(Delta::date).min(order).orElseThrow().toString();
    }

    private static Map<String, AuditField> counts(int added, int changed, int unchanged) {
        Map<String, AuditField> result = new LinkedHashMap<>();
        result.put("added", AuditField.visible("新增日期数", added));
        result.put("changed", AuditField.visible("修改日期数", changed));
        result.put("unchanged", AuditField.visible("未变更日期数", unchanged));
        return result;
    }

    private static void values(Map<String, AuditField> fields, Values values) {
        fields.put("isTradingDay", AuditField.visible("是否交易日", values.trading()));
        fields.put("holidayName", AuditField.visible("假期名称", values.holiday()));
        fields.put("source", AuditField.visible("来源", values.source().name()));
        fields.put("remark", AuditField.visible("备注", values.remark()));
    }
}
