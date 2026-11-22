package me.lendev.flow.workbench.series;

import static java.time.DayOfWeek.MONDAY;
import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.MONTHS;
import static java.time.temporal.ChronoUnit.WEEKS;
import static java.time.temporal.TemporalAdjusters.previousOrSame;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 从原始锚点计算自然日周期，历史期次无需逐条扫描。
 */
public final class SeriesRuleDates {

    static final LocalDate MIN_DATE = LocalDate.of(1000, 1, 1);
    static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

    private SeriesRuleDates() {
    }

    public enum Frequency {DAILY, WEEKLY, MONTHLY, YEARLY, TRADING_DAILY}

    public enum MonthDayMode {ANCHOR, MONTH_END}

    /**
     * 自然日周期计算参数。weekdaysMask 按位表示周一至周日，
     * until 为 null 表示不限制结束锚点。
     */
    record Rule(Frequency frequency, int interval, Integer weekdaysMask, MonthDayMode monthDayMode,
                LocalDate anchor, LocalDate until) {

        LocalDate end() {
            return until == null ? MAX_DATE : until;
        }
    }

    /**
     * 返回不早于指定日期的首个有效锚点，规则已结束时返回空。
     *
     * @param rule       周期计算参数，不支持 TRADING_DAILY
     * @param lowerBound 搜索下界（含）
     * @return 首个有效锚点，无后续锚点时为 null
     * @throws IllegalArgumentException 频率为 TRADING_DAILY
     */
    static LocalDate onOrAfter(Rule rule, LocalDate lowerBound) {
        LocalDate first = lowerBound.isAfter(rule.anchor()) ? lowerBound : rule.anchor();
        if (first.isAfter(rule.end())) {
            return null;
        }
        LocalDate next = switch (rule.frequency()) {
            case DAILY -> rule.anchor().plusDays(alignUp(DAYS.between(rule.anchor(), first), rule.interval()));
            case WEEKLY -> weekly(rule, first);
            case MONTHLY -> byMonth(rule, first, rule.interval());
            case YEARLY -> byMonth(rule, first, rule.interval() * 12L);
            case TRADING_DAILY -> throw new IllegalArgumentException("交易日周期依赖市场日历");
        };
        return next == null || next.isAfter(rule.end()) ? null : next;
    }

    private static LocalDate weekly(Rule rule, LocalDate first) {
        LocalDate anchorWeek = rule.anchor().with(previousOrSame(MONDAY));
        LocalDate firstWeek = first.with(previousOrSame(MONDAY));
        LocalDate week = anchorWeek.plusWeeks(alignUp(WEEKS.between(anchorWeek, firstWeek), rule.interval()));
        LocalDate next = selectedDay(week, first, rule.weekdaysMask());
        return next == null ? selectedDay(week.plusWeeks(rule.interval()), first, rule.weekdaysMask()) : next;
    }

    private static LocalDate selectedDay(LocalDate monday, LocalDate first, int mask) {
        for (int day = 0; day < 7; day++) {
            if ((mask & (1 << day)) != 0) {
                LocalDate candidate = monday.plusDays(day);
                if (!candidate.isBefore(first)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static LocalDate byMonth(Rule rule, LocalDate first, long step) {
        YearMonth anchorMonth = YearMonth.from(rule.anchor());
        long months = MONTHS.between(anchorMonth, YearMonth.from(first));
        long last = MONTHS.between(anchorMonth, YearMonth.from(rule.end()));
        for (long period = alignUp(months, step); period <= last; period += step) {
            YearMonth month = anchorMonth.plusMonths(period);
            int day = rule.monthDayMode() == MonthDayMode.MONTH_END ? month.lengthOfMonth()
                    : rule.anchor().getDayOfMonth();
            if (month.isValidDay(day)) {
                LocalDate candidate = month.atDay(day);
                if (!candidate.isBefore(first)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static long alignUp(long value, long step) {
        return Math.ceilDiv(value, step) * step;
    }
}
