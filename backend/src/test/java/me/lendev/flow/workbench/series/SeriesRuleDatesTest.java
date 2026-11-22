package me.lendev.flow.workbench.series;

import static me.lendev.flow.workbench.series.SeriesRuleDates.Frequency.*;
import static me.lendev.flow.workbench.series.SeriesRuleDates.MonthDayMode.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class SeriesRuleDatesTest {

    @Test
    void alignsWeeklyChoicesToTheAnchorMondayAndSkipsBeforeTheAnchor() {
        var rule = new SeriesRuleDates.Rule(WEEKLY, 2, 5, null, date("2026-11-04"), null);
        assertEquals(date("2026-11-04"), SeriesRuleDates.onOrAfter(rule, date("2026-11-01")));
        assertEquals(date("2026-11-16"), SeriesRuleDates.onOrAfter(rule, date("2026-11-05")));
        assertEquals(date("2026-11-18"), SeriesRuleDates.onOrAfter(rule, date("2026-11-17")));
    }

    @Test
    void skipsMonthsThatDoNotHaveTheAnchorDayAndHonorsTheInclusiveEnd() {
        var rule = new SeriesRuleDates.Rule(MONTHLY, 1, null, ANCHOR,
                date("2026-01-31"), date("2026-03-31"));
        assertEquals(date("2026-03-31"), SeriesRuleDates.onOrAfter(rule, date("2026-02-01")));
        assertNull(SeriesRuleDates.onOrAfter(rule, date("2026-04-01")));
    }

    @Test
    void computesMonthEndWithoutChangingCadence() {
        var rule = new SeriesRuleDates.Rule(MONTHLY, 2, null, MONTH_END, date("2025-12-20"), null);
        assertEquals(date("2025-12-31"), SeriesRuleDates.onOrAfter(rule, date("2025-12-20")));
        assertEquals(date("2026-02-28"), SeriesRuleDates.onOrAfter(rule, date("2026-01-01")));
    }

    @Test
    void skipsNonLeapYearsWithoutClampingFebruary29() {
        var rule = new SeriesRuleDates.Rule(YEARLY, 1, null, ANCHOR, date("2024-02-29"), null);
        assertEquals(date("2028-02-29"), SeriesRuleDates.onOrAfter(rule, date("2024-03-01")));
    }

    @Test
    void fastForwardsDailyCadenceFromTheOriginalAnchor() {
        var rule = new SeriesRuleDates.Rule(DAILY, 3, null, null, date("2023-01-01"), null);
        assertEquals(date("2026-11-11"), SeriesRuleDates.onOrAfter(rule, date("2026-11-09")));
    }

    private static LocalDate date(String value) {
        return LocalDate.parse(value);
    }
}
