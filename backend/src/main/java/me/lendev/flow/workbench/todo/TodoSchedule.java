package me.lendev.flow.workbench.todo;

import static me.lendev.flow.workbench.todo.TodoModels.*;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.calendar.TradingCalendarService;
import me.lendev.flow.common.exception.BadRequestException;
import org.springframework.stereotype.Service;

/**
 * 校验固定排期并通过交易日历计算相对交易日排期。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TodoSchedule {

    private static final ZoneId WORKBENCH_ZONE = ZoneId.of("Asia/Shanghai");
    private final TradingCalendarService calendar;
    private final Clock clock;

    /**
     * 解析后的排期日期与市场参数项 ID，固定排期市场 ID 为 null。
     */
    public record Resolved(Dates dates, Long marketItemId) {

    }

    /**
     * 工作台业务时区（Asia/Shanghai）的当前日期。
     */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(WORKBENCH_ZONE));
    }

    /**
     * 当前 UTC 时间，截断到毫秒以匹配数据库精度。
     */
    public LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * 返回排期及本次计算已经验证的连续覆盖区间。
     */
    public SchedulePreview preview(Write request) {
        Dates dates = resolve(request).dates();
        var covered = request.tradeBaseDate() == null ? java.util.List.<LocalDate>of()
                : Stream.of(request.tradeBaseDate(), dates.startDate(), dates.endDate(), dates.dueDate())
                .filter(Objects::nonNull).sorted().toList();
        return new SchedulePreview(dates.startDate(), dates.endDate(), dates.dueDate(),
                covered.isEmpty() ? null : covered.getFirst(), covered.isEmpty() ? null : covered.getLast());
    }

    /**
     * 按一致的市场日历快照校验 T，解析所有有效偏移。
     * 携带预览确认时，解析结果与预览不一致视为日历已变化。
     *
     * @param request 待办写入请求
     * @return 解析后的排期日期与市场 ID
     * @throws BadRequestException   排期参数不合法或日历无法解析
     * @throws TodoConflictException 解析结果与预览确认不一致
     */
    public Resolved resolve(Write request) {
        var mode = mode(request);
        var fixed = new Dates(request.startDate(), request.endDate(), request.dueDate());
        if (mode == ScheduleMode.TRADING) {
            if (!empty(fixed) || request.marketCode() == null || request.tradeBaseDate() == null) {
                throw invalid();
            }
            var offsets = Stream.of(request.startTradeOffset(), request.endTradeOffset(), request.dueTradeOffset())
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            if (offsets.isEmpty() || (request.startTradeOffset() == null) != (request.endTradeOffset() == null)) {
                throw invalid();
            }
            var resolved = calendar.scheduleOffsets(request.marketCode(), request.tradeBaseDate(), offsets);
            var dates = new Dates(at(resolved, request.startTradeOffset()), at(resolved, request.endTradeOffset()),
                    at(resolved, request.dueTradeOffset()));
            validateDates(dates);
            if (request.preview() != null && !dates.equals(request.preview())) {
                log.info("待办交易排期确认过期，市场={}，基准日={}", request.marketCode(), request.tradeBaseDate());
                throw new TodoConflictException("error.todo.schedule-changed");
            }
            return new Resolved(dates, resolved.marketItemId());
        }
        if (request.marketCode() != null || request.tradeBaseDate() != null || request.startTradeOffset() != null
                || request.endTradeOffset() != null || request.dueTradeOffset() != null
                || (mode == ScheduleMode.NONE) != empty(fixed)) {
            throw invalid();
        }
        validateDates(fixed);
        return new Resolved(fixed, null);
    }

    static ScheduleMode mode(Write request) {
        try {
            return request.scheduleMode() == null ? ScheduleMode.NONE : ScheduleMode.valueOf(request.scheduleMode());
        } catch (IllegalArgumentException invalid) {
            throw invalid();
        }
    }

    private static LocalDate at(TradingCalendarService.ScheduleOffsets result, Integer offset) {
        return offset == null ? null : result.dates().get(offset);
    }

    private static boolean empty(Dates dates) {
        return dates.startDate() == null && dates.endDate() == null && dates.dueDate() == null;
    }

    private static void validateDates(Dates dates) {
        if ((dates.startDate() == null) != (dates.endDate() == null)
                || dates.startDate() != null && dates.endDate().isBefore(dates.startDate())
                || Stream.of(dates.startDate(), dates.endDate(), dates.dueDate())
                .filter(Objects::nonNull).anyMatch(day -> day.getYear() < 1000 || day.getYear() > 9999)) {
            throw invalid();
        }
    }

    private static BadRequestException invalid() {
        return BadRequestException.localized("error.todo.schedule");
    }
}
