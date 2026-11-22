package me.lendev.flow.workbench.series;

import static me.lendev.flow.workbench.series.SeriesModels.*;
import static me.lendev.flow.workbench.series.SeriesRuleDates.MAX_DATE;
import static me.lendev.flow.workbench.series.SeriesRuleDates.MIN_DATE;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.calendar.TradingCalendarService;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.exception.BizException;
import me.lendev.flow.workbench.todo.TodoModels;
import me.lendev.flow.workbench.todo.TodoModels.Dates;
import me.lendev.flow.workbench.todo.TodoSchedule;
import org.springframework.stereotype.Component;

/**
 * 在预览和批量生成中统一计算锚点和实际业务日期。
 */
@Component
@RequiredArgsConstructor
class SeriesSchedule {

    private static final int PREVIEW_OCCURRENCES = 3;
    private static final int PREVIEW_ANCHORS = 512;
    private final TradingCalendarService calendar;
    private final TodoSchedule todoSchedule;

    /**
     * 自然日周期按有效日期直接跳转，交易周期按连续覆盖的交易日从已处理锚点向后计数。 返回空表示规则已越过最后允许的锚点。
     */
    LocalDate next(Write rule, LocalDate effective, LocalDate previous) {
        if (rule.trading()) {
            if (previous == null) {
                return rule.anchorDate();
            }
            LocalDate until = rule.untilDate() == null ? MAX_DATE : rule.untilDate();
            return calendar(() -> calendar.nextRecurrenceBase(rule.marketCode(), previous, rule.intervalValue(), until),
                    previous);
        }

        long dueOffset = rule.dueOffsetDays() == null ? 0L : rule.dueOffsetDays();
        long minOffset = rule.durationDays() == null ? dueOffset : Math.min(0L, dueOffset);
        long maxOffset = Math.max(dueOffset, rule.durationDays() == null ? 0L : rule.durationDays() - 1L);
        LocalDate first = previous == null ? effective.minusDays(minOffset) : previous.plusDays(1);
        LocalDate earliestValid = MIN_DATE.minusDays(Math.min(0L, minOffset));
        LocalDate last = MAX_DATE.minusDays(maxOffset);
        if (rule.untilDate() != null && rule.untilDate().isBefore(last)) {
            last = rule.untilDate();
        }
        var recurrence = new SeriesRuleDates.Rule(rule.frequency(), rule.intervalValue(), rule.weekdaysMask(),
                rule.monthDayMode(), rule.anchorDate(), last);
        return SeriesRuleDates.onOrAfter(recurrence, first.isBefore(earliestValid) ? earliestValid : first);
    }

    /**
     * 计算锚点对应的实际业务日期。交易周期依赖市场日历解析偏移，
     * 自然日周期按计划天数与截止偏移直接推算。
     *
     * @param rule 规则内容
     * @param base 本期锚点日期
     * @return 含开始、结束与截止日期的期次
     * @throws Blocked 交易日历缺失、基准日休市或市场停用
     */
    Occurrence occurrence(Write rule, LocalDate base) {
        if (rule.trading()) {
            return Occurrence.of(base, calendar(() -> todoSchedule.resolve(todoWrite(rule, base, null)).dates(), base));
        }
        LocalDate start = rule.durationDays() == null ? null : base;
        LocalDate end = start == null ? null : base.plusDays(rule.durationDays() - 1L);
        LocalDate due = rule.dueOffsetDays() == null ? null : base.plusDays(rule.dueOffsetDays());
        return Occurrence.of(base, new Dates(start, end, due));
    }

    /**
     * 将周期规则转换为单期待办的写入请求。交易周期只传锚点与交易日偏移，
     * 自然日周期传解析后的具体日期。
     */
    static TodoModels.Write todoWrite(Write rule, LocalDate base, Dates dates) {
        boolean trading = rule.trading();
        return new TodoModels.Write(null, rule.title(), rule.description(), rule.assigneeUserId(), rule.priority(),
                trading ? "TRADING" : "FIXED", trading ? null : dates.startDate(), trading ? null : dates.endDate(),
                trading ? null : dates.dueDate(), rule.marketCode(), trading ? base : null, rule.startTradeOffset(),
                rule.endTradeOffset(), rule.dueTradeOffset(), rule.dimensions(), null, null);
    }

    /**
     * 预览生效日之后的前三期。最多向后搜索有限个锚点，
     * 搜索超限或日历受阻时携带阻塞信息返回已算出的期次。
     */
    Preview preview(Write rule, LocalDate effective, SeriesRepository.Coverage coverage) {
        List<Occurrence> occurrences = new ArrayList<>();
        LocalDate previous = null;
        try {
            for (int checked = 0; checked < PREVIEW_ANCHORS; checked++) {
                LocalDate base = next(rule, effective, previous);
                if (base == null) {
                    return preview(coverage, occurrences, null);
                }
                Occurrence occurrence = occurrence(rule, base);
                if (!occurrence.occurrenceDate().isBefore(effective)) {
                    occurrences.add(occurrence);
                    if (occurrences.size() == PREVIEW_OCCURRENCES) {
                        return preview(coverage, occurrences, null);
                    }
                }
                previous = base;
            }
            return preview(coverage, occurrences, new Blocked(BlockReason.SEARCH_LIMIT, previous));
        } catch (Blocked blocked) {
            return preview(coverage, occurrences, blocked);
        }
    }

    private static Preview preview(SeriesRepository.Coverage coverage, List<Occurrence> occurrences, Blocked blocked) {
        return new Preview(List.copyOf(occurrences), coverage.start(), coverage.end(),
                blocked == null ? null : blocked.date, blocked == null ? null : blocked.reason);
    }

    private static <T> T calendar(Supplier<T> action, LocalDate anchor) {
        try {
            return action.get();
        } catch (BizException exception) {
            BlockReason reason = switch (exception.getCode()) {
                case TRADING_CALENDAR_COVERAGE_MISSING -> BlockReason.COVERAGE_MISSING;
                case TRADING_CALENDAR_BASE_CLOSED -> BlockReason.BASE_CLOSED;
                default -> throw exception;
            };
            var args = exception.getApiMessage().arguments();
            LocalDate date = args.length > 1 && args[1] instanceof LocalDate value ? value : anchor;
            throw new Blocked(reason, date);
        } catch (BadRequestException exception) {
            String key = exception.getApiMessage() == null ? "" : exception.getApiMessage().key();
            throw switch (key) {
                case "error.trading-calendar.market" -> new Blocked(BlockReason.MARKET_DISABLED, anchor);
                case "error.trading-calendar.limit" -> new Blocked(BlockReason.SEARCH_LIMIT, anchor);
                default -> exception;
            };
        }
    }

    /**
     * 可恢复的生成阻塞，携带原因与受阻日期，由调用方延期重试而非回滚。
     */
    static final class Blocked extends RuntimeException {

        final BlockReason reason;
        final LocalDate date;

        Blocked(BlockReason reason, LocalDate date) {
            super(reason.name());
            this.reason = reason;
            this.date = date;
        }
    }
}
