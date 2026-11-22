package me.lendev.flow.workbench.series;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import me.lendev.flow.audit.AuditSystemContext;
import me.lendev.flow.support.ApiTestBase;
import me.lendev.flow.workbench.todo.TodoSchedule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class SeriesApiTest extends ApiTestBase {

    private static final String PATH = "/workbench/todo-series";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TodoSchedule schedule;

    @Autowired
    private SeriesService series;

    @Test
    void futureEditKeepsSingleOccurrenceEditsAndRejectsStaleImpact() throws Exception {
        String login = loginAsAdmin();
        LocalDate today = schedule.today();
        var original = map("title", "Cycle", "frequency", "DAILY", "intervalValue", 1,
                "anchorDate", today, "untilDate", today.plusDays(4), "dueOffsetDays", 0);
        var created = data(postJson(PATH, login, original));
        long id = created.path("id").asLong();
        assertEquals("Cycle", created.path("title").asString());
        generate(id);
        generate(id);
        assertEquals(5, count(id, "OPEN"));

        jdbc.update("UPDATE wb_todo SET occurrenceEdited = TRUE, version = 1 WHERE seriesId = ? AND occurrenceDate = ?",
                id, today.plusDays(1));
        var change = map("title", "New cycle", "frequency", "DAILY", "intervalValue", 1,
                "anchorDate", today, "untilDate", today.plusDays(4), "dueOffsetDays", 0);
        String url = PATH + "/" + id;
        var first = data(postJson(url + "/change-preview", login, map("action", "EDIT", "rule", change)));
        assertEquals(3, first.path("affected").size());
        jdbc.update("UPDATE wb_todo SET version = 1 WHERE seriesId = ? AND occurrenceDate = ?", id, today.plusDays(2));
        var stale = map("token", first.path("token").asString(), "rule", change);
        body(patchJson(url, login, stale), 409);
        assertEquals(5, count(id, "OPEN"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void tradingGapCommitsTheSuccessfulPrefixAndResumesWithTheSameCadence() throws Exception {
        String login = loginAsAdmin();
        LocalDate today = schedule.today();
        var request = map("title", "Trade cycle", "frequency", "TRADING_DAILY", "intervalValue", 1,
                "anchorDate", today, "untilDate", today.plusDays(2), "marketCode", "CN", "dueTradeOffset", 1);
        long id = 0L;
        try {
            calendarDay(today, true);
            calendarDay(today.plusDays(1), true);
            calendarDay(today.plusDays(3), true);
            var preview = data(postJson(PATH + "/preview", login, request));
            assertEquals(today.plusDays(1).toString(), preview.path("occurrences").get(0).path("dueDate").asString());
            assertEquals(today.plusDays(2).toString(), preview.path("blockedDate").asString());
            id = data(postJson(PATH, login, request)).path("id").asLong();

            generate(id);
            assertEquals(1, count(id, "OPEN"));
            var blocked = data(getJson(PATH, login)).get(0);
            assertEquals("COVERAGE_MISSING", blocked.path("blockReason").asString());
            assertEquals(today.toString(), blocked.path("lastBaseDate").asString());
            var audit = objectMapper.readTree(jdbc.queryForObject("""
                    SELECT payload FROM sys_audit_outbox WHERE operationCode = 'workbench.todo.series.generate'
                    ORDER BY id DESC LIMIT 1
                    """, String.class));
            assertEquals("SYSTEM", audit.path("actor").path("type").asString());
            assertEquals(Long.toString(id), audit.path("objects").get(0).path("object").path("objectId").asString());

            calendarDay(today, false);
            calendarDay(today.plusDays(2), true);
            jdbc.update("UPDATE wb_todo_series SET nextCheckAt = TIMESTAMP '2000-01-01 00:00:00' WHERE id = ?", id);
            generate(id);
            assertEquals(3, count(id, "OPEN"));
            var finished = data(getJson(PATH, login)).get(0);
            assertEquals("FINISHED", finished.path("status").asString());
            assertTrue(finished.path("blockedDate").isNull());
        } finally {
            jdbc.update("DELETE FROM wb_todo WHERE seriesId = ?", id);
            jdbc.update("DELETE FROM wb_todo_series WHERE id = ?", id);
            jdbc.update("""
                    DELETE FROM sys_trading_calendar_day WHERE calendarDate BETWEEN ? AND ?
                    AND marketItemId IN (
                        SELECT p.id FROM sys_param_item p JOIN sys_param_group g ON g.id = p.groupId
                        WHERE g.code = 'TRADING_MARKET' AND p.code = 'CN'
                    )
                    """, today, today.plusDays(3));
        }
    }

    @Test
    void generatesATradingAnchorPastTheWindowWhenItsActualDueDateIsInRange() throws Exception {
        String login = loginAsAdmin();
        LocalDate horizon = schedule.today().plusDays(89);
        LocalDate anchor = horizon.plusDays(1);
        calendarDay(horizon, true);
        calendarDay(anchor, true);
        var request = map("title", "Trade cycle", "frequency", "TRADING_DAILY", "intervalValue", 1,
                "anchorDate", anchor, "untilDate", anchor, "marketCode", "CN", "dueTradeOffset", -1);
        long id = data(postJson(PATH, login, request)).path("id").asLong();
        generate(id);
        assertEquals(horizon, jdbc.queryForObject("SELECT occurrenceDate FROM wb_todo WHERE seriesId = ?",
                LocalDate.class, id));
        assertEquals(anchor, jdbc.queryForObject("SELECT seriesBaseDate FROM wb_todo WHERE seriesId = ?",
                LocalDate.class, id));
    }

    @Test
    void newRuleRevisionRebuildsCancelledAnchorsAndStopIncludesEditedFutureOccurrences() throws Exception {
        String login = loginAsAdmin();
        LocalDate today = schedule.today();
        var request = map("title", "Before", "frequency", "DAILY", "intervalValue", 1,
                "anchorDate", today, "untilDate", today.plusDays(3), "dueOffsetDays", 0);
        long id = data(postJson(PATH, login, request)).path("id").asLong();
        String url = PATH + "/" + id;
        generate(id);
        jdbc.update("UPDATE wb_todo SET occurrenceEdited = TRUE, version = 1 WHERE seriesId = ? AND occurrenceDate = ?",
                id, today.plusDays(1));

        request.put("title", "After");
        var edit = data(postJson(url + "/change-preview", login, map("action", "EDIT", "rule", request)));
        assertEquals(2, edit.path("affected").size());
        var revised = data(patchJson(url, login, map("token", edit.path("token").asString(), "rule", request)));
        assertEquals(2, revised.path("ruleRevision").asInt());
        assertEquals(today.plusDays(1).toString(), revised.path("effectiveFromDate").asString());
        generate(id);
        assertEquals(4, count(id, "OPEN"));
        assertEquals(2, count(id, "CANCELLED"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM wb_todo WHERE seriesId = ? AND seriesRevision = 2",
                Integer.class, id));

        var stop = data(postJson(url + "/change-preview", login, map("action", "STOP")));
        assertEquals(3, stop.path("affected").size());
        var stopped = data(postJson(url + "/stop", login, map("token", stop.path("token").asString())));
        assertEquals("STOPPED", stopped.path("status").asString());
        assertTrue(stopped.path("nextCheckAt").isNull());
        assertEquals(1, count(id, "OPEN"));
        assertEquals(5, count(id, "CANCELLED"));
    }

    @Test
    void workerBatchesDueRulesAndDefersTheNextAnchorOutsideTheWindow() throws Exception {
        String login = loginAsAdmin();
        LocalDate today = schedule.today();
        var request = map("title", "Daily batch", "frequency", "DAILY", "intervalValue", 1,
                "anchorDate", today, "untilDate", today.plusDays(90), "dueOffsetDays", 0);
        long id = data(postJson(PATH, login, request)).path("id").asLong();
        var worker = new SeriesWorker(series);
        worker.tick();
        assertEquals(32, count(id, "OPEN"));
        worker.tick();
        assertEquals(64, count(id, "OPEN"));
        worker.tick();
        assertEquals(90, count(id, "OPEN"));
        assertEquals(today.plusDays(90), jdbc.queryForObject("SELECT nextBaseDate FROM wb_todo_series WHERE id = ?",
                LocalDate.class, id));
        assertNotNull(jdbc.queryForObject("SELECT nextCheckAt FROM wb_todo_series WHERE id = ?",
                java.time.LocalDateTime.class, id));
        worker.tick();
        assertEquals(90, count(id, "OPEN"));
    }

    @Test
    void validatesCadenceFieldsAndPreservesTheRevisionOfAnUnchangedRule() throws Exception {
        String login = loginAsAdmin();
        LocalDate today = schedule.today();
        var request = map("title", "One occurrence", "frequency", "DAILY", "intervalValue", 1,
                "anchorDate", today, "untilDate", today, "dueOffsetDays", 0);
        var preview = data(postJson(PATH + "/preview", login, request));
        assertEquals(1, preview.path("occurrences").size());
        var created = data(postJson(PATH, login, request));
        String url = PATH + "/" + created.path("id").asLong();
        var edit = data(postJson(url + "/change-preview", login, map("action", "EDIT", "rule", request)));
        var unchanged = data(patchJson(url, login, map("token", edit.path("token").asString(), "rule", request)));
        assertEquals(created.path("ruleRevision"), unchanged.path("ruleRevision"));
        body(postJson(PATH + "/999999/change-preview", login, map("action", "STOP")), 404);

        for (var override : List.of(map("untilDate", today.minusDays(1)), map("weekdaysMask", 1),
                map("monthDayMode", "ANCHOR"), map("priority", "UNKNOWN"),
                map("dimensions", Map.of(6, 1)), map("frequency", "TRADING_DAILY"),
                map("marketCode", "UNKNOWN"))) {
            var invalid = new java.util.HashMap<>(request);
            invalid.putAll(override);
            body(postJson(PATH + "/preview", login, invalid), 400);
        }
        var invalidDuration = new java.util.HashMap<>(request);
        invalidDuration.put("durationDays", 0);
        code(postJson(PATH + "/preview", login, invalidDuration), 400);
        var unknownMarket = map("title", "Unknown market", "frequency", "TRADING_DAILY", "intervalValue", 1,
                "anchorDate", today, "marketCode", "UNKNOWN", "dueTradeOffset", 0);
        body(postJson(PATH + "/preview", login, unknownMarket), 400);
    }

    private int count(long id, String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM wb_todo WHERE seriesId = ? AND status = ?",
                Integer.class, id, status);
    }

    private void generate(long id) {
        try (var identity = AuditSystemContext.open("todo-series-test")) {
            series.generate(id);
        }
    }

    private void calendarDay(LocalDate date, boolean trading) {
        jdbc.update("""
                INSERT INTO sys_trading_calendar_day (marketItemId, calendarDate, calendarDate8, isTradingDay, source)
                SELECT p.id, ?, ?, ?, 'MANUAL'
                FROM sys_param_item p JOIN sys_param_group g ON g.id = p.groupId
                WHERE g.code = 'TRADING_MARKET' AND p.code = 'CN'
                ON DUPLICATE KEY UPDATE isTradingDay = VALUES(isTradingDay)
                """, date, date.toString().replace("-", ""), trading);
    }
}
