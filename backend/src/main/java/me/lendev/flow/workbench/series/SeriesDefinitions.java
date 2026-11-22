package me.lendev.flow.workbench.series;

import static me.lendev.flow.calendar.TradingCalendarService.MAX_SCHEDULE_OFFSET;
import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO_ASSIGN;
import static me.lendev.flow.workbench.series.SeriesRuleDates.MAX_DATE;
import static me.lendev.flow.workbench.series.SeriesRuleDates.MIN_DATE;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.workbench.series.SeriesModels.Write;
import me.lendev.flow.workbench.series.SeriesRuleDates.Frequency;
import me.lendev.flow.workbench.series.SeriesRuleDates.MonthDayMode;
import me.lendev.flow.workbench.todo.TodoAccess;
import me.lendev.flow.workbench.todo.TodoAssigneeException;
import me.lendev.flow.workbench.todo.TodoModels.Priority;
import me.lendev.flow.workbench.todo.TodoService;
import org.springframework.stereotype.Component;

/**
 * 校验并规范化周期待办规则，生成预览和持久化共用的规则定义。
 */
@Component
@RequiredArgsConstructor
class SeriesDefinitions {

    private final SeriesRepository repository;
    private final TodoService todos;

    /**
     * 规范化后的规则定义。marketItemId 仅交易周期非空，指向启用的市场参数项。
     */
    record Definition(Write write, Long marketItemId) {

    }

    /**
     * 保留已有规则中未提交的负责人和维度，校验新负责人权限以及周期参数。
     *
     * @param input    本次提交的规则内容
     * @param existing 已有规则内容，新建时为 null；未提交的负责人与维度沿用已有值
     * @param managed  管理身份可跳过改派负责人的权限校验
     * @return 规范化后的规则定义
     * @throws BadRequestException   参数不合法、市场停用或维度槽位越界
     * @throws TodoAssigneeException 负责人不存在或已停用
     */
    Definition normalize(Write input, Write existing, boolean managed) {
        if (input == null || input.title() == null || input.title().isBlank()
                || input.title().strip().length() > 200 || input.frequency() == null
                || input.description() != null && input.description().length() > 10000
                || input.intervalValue() == null || input.intervalValue() <= 0
                || !validDate(input.anchorDate()) || input.untilDate() != null
                && (!validDate(input.untilDate()) || input.untilDate().isBefore(input.anchorDate()))) {
            throw invalid();
        }
        int viewer = TodoAccess.viewer();
        int previousAssignee = existing == null ? viewer : existing.assigneeUserId();
        int assignee = input.assigneeUserId() == null ? previousAssignee : input.assigneeUserId();
        if (!managed && assignee != previousAssignee) {
            TodoAccess.permit(WORKBENCH_TODO_ASSIGN);
        }
        todos.requireActiveUser(assignee);
        var monthMode = validateCadence(input);
        String market = input.marketCode() == null ? null : input.marketCode().strip().toUpperCase(Locale.ROOT);
        Long marketItemId = validateSchedule(input, market);
        Map<Integer, Long> selected = normalizedDimensions(input, existing);
        String description = input.description() == null || input.description().isBlank() ? null : input.description();
        var normalized = new Write(input.title().strip(), description, assignee, priority(input.priority()),
                input.frequency(), input.intervalValue(), input.weekdaysMask(), monthMode, input.anchorDate(),
                input.untilDate(), input.durationDays(), input.dueOffsetDays(), market, input.startTradeOffset(),
                input.endTradeOffset(), input.dueTradeOffset(), selected);
        return new Definition(normalized, marketItemId);
    }

    private static Map<Integer, Long> normalizedDimensions(Write input, Write existing) {
        Map<Integer, Long> requested = input.dimensions() == null && existing != null
                ? existing.dimensions() : input.dimensions();
        Map<Integer, Long> selected = new TreeMap<>();
        if (requested != null) {
            requested.forEach((slot, item) -> {
                if (slot == null || slot < 1 || slot > 5 || item != null && item < 1) {
                    throw BadRequestException.localized("error.todo.dimension.slot");
                }
                if (item != null) {
                    selected.put(slot, item);
                }
            });
        }
        return Collections.unmodifiableMap(selected);
    }

    private static MonthDayMode validateCadence(Write input) {
        Integer mask = input.weekdaysMask();
        boolean weekly = input.frequency() == Frequency.WEEKLY;
        boolean monthlyOrYearly = input.frequency() == Frequency.MONTHLY || input.frequency() == Frequency.YEARLY;
        if (weekly ? mask == null || mask < 1 || mask > 127 : mask != null) {
            throw invalid();
        }
        if (!monthlyOrYearly && input.monthDayMode() != null) {
            throw invalid();
        }
        return monthlyOrYearly && input.monthDayMode() == null ? MonthDayMode.ANCHOR : input.monthDayMode();
    }

    private Long validateSchedule(Write input, String market) {
        Integer start = input.startTradeOffset();
        Integer end = input.endTradeOffset();
        Integer due = input.dueTradeOffset();
        if (input.trading()) {
            if (market == null || input.intervalValue() > MAX_SCHEDULE_OFFSET
                    || input.durationDays() != null || input.dueOffsetDays() != null
                    || (start == null) != (end == null) || start == null && due == null
                    || start != null && start > end || !validOffset(start) || !validOffset(end) || !validOffset(due)) {
                throw invalid();
            }
            Long marketId = repository.activeMarket(market);
            if (marketId == null) {
                throw BadRequestException.localized("error.trading-calendar.market", market);
            }
            return marketId;
        }
        Integer duration = input.durationDays();
        if (market != null || start != null || end != null || due != null
                || duration != null && duration <= 0 || duration == null && input.dueOffsetDays() == null) {
            throw invalid();
        }
        return null;
    }

    private static String priority(String value) {
        try {
            return value == null ? Priority.NORMAL.name() : Priority.valueOf(value).name();
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static boolean validOffset(Integer offset) {
        return offset == null || Math.abs((long) offset) <= MAX_SCHEDULE_OFFSET;
    }

    private static boolean validDate(LocalDate date) {
        return date != null && !date.isBefore(MIN_DATE) && !date.isAfter(MAX_DATE);
    }

    /**
     * 返回周期待办规则参数无效的异常。
     */
    static BadRequestException invalid() {
        return BadRequestException.localized("error.todo.series.rule");
    }
}
