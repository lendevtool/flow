package me.lendev.flow.calendar;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.time.LocalDate;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import me.lendev.flow.calendar.TradingCalendarModels.Source;
import me.lendev.flow.calendar.TradingCalendarRepository.Delta;
import me.lendev.flow.calendar.TradingCalendarRepository.Values;
import me.lendev.flow.common.exception.BadRequestException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

class TradingCalendarApiTest extends ApiTestBase {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TradingCalendarRepository repository;
    @Autowired
    private TradingCalendarService calendar;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollsBackInsertedDaysWhenTheUpdateBatchFails() {
        var market = repository.markets().stream().filter(item -> item.market().code().equals("CN"))
                .findFirst().orElseThrow();
        var existingDate = LocalDate.of(2095, 1, 3);
        var newDate = existingDate.plusDays(1);
        var initial = new Values(true, null, Source.INIT, null);
        var invalid = new Values(false, "X".repeat(101), Source.MANUAL, null);
        var transaction = new TransactionTemplate(transactionManager);
        try {
            transaction.executeWithoutResult(status ->
                    repository.apply(List.of(new Delta(market, existingDate, null, initial)), 1L));

            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                var before = repository.selected(market.id(), List.of(existingDate), true).getFirst();
                repository.apply(List.of(new Delta(market, newDate, null, initial),
                        new Delta(market, existingDate, before, invalid)), 1L);
            })).hasStackTraceContaining("Value too long for column");

            var remaining = repository.range(market.id(), existingDate, newDate, true);
            assertEquals(1, remaining.size());
            assertEquals(existingDate, remaining.getFirst().date());
            assertEquals(initial, remaining.getFirst().values());
        } finally {
            jdbc.update("DELETE FROM sys_trading_calendar_day WHERE marketItemId = ? AND calendarDate IN (?, ?)",
                    market.id(), existingDate, newDate);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void initializationPreservesExceptionsAndOffsetsStopAtTheFirstCoverageGap() throws Exception {
        String token = loginAsAdmin();
        try {
            assertEquals("CN", data(getJson("/trading-calendar/markets", token)).get(0).path("code").asString());
            var initial = map("year", 2096, "marketCodes", List.of("CN"));
            var preview = data(postJson("/trading-calendar/initialize/preview", token, initial));
            assertEquals(366, preview.path("added").asInt());
            assertEquals(0, rows(2096));
            apply(token, "initialize", initial);
            assertEquals(366, rows(2096));

            var close = batch("2096-12-31", false, "Year End");
            apply(token, "batch", close);
            assertEquals(0, data(postJson("/trading-calendar/initialize/preview", token, initial)).path("added").asInt());
            var day = data(getJson("/trading-calendar/status", token,
                    "marketCode", "CN", "date", "2096-12-31"));
            assertFalse(day.path("isTradingDay").asBoolean());
            assertEquals("20961231", day.path("calendarDate8").asString());
            assertEquals("Year End", day.path("holidayName").asString());
            assertEquals("MANUAL", day.path("source").asString());

            assertEquals("2096-12-28", data(offset(token, "2096-12-31", -1)).path("date").asString());
            code(offset(token, "2096-12-31", 0), 12002);
            // The Jan 3 entry must not be selected while Jan 1 and Jan 2 are missing.
            apply(token, "batch", batch("2097-01-03", true, null));
            code(offset(token, "2096-12-31", 1), 12001);
            apply(token, "initialize", map("year", 2097, "marketCodes", List.of("CN")));
            assertEquals("2097-01-01", data(offset(token, "2096-12-31", 1)).path("date").asString());
        } finally {
            clear(2096, 2097);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void staleBatchCannotOverwriteChangesAndAuditReportsCommittedMutation() throws Exception {
        String token = loginAsAdmin();
        try {
            var close = batch("2098-01-02", false, "New Year");
            close.put("fingerprint", data(postJson("/trading-calendar/batch/preview", token, close)).path("fingerprint").asString());
            apply(token, "batch", batch("2098-01-02", true, null));
            body(postJson("/trading-calendar/batch/apply", token, close), 409);
            JsonNode rejected = latestAudit("system.trading-calendar.batch", "FAILURE");
            assertEquals("CONFLICT", rejected.path("errorCode").asString());
            assertEquals("409", rejected.path("businessCode").asString());
            var day = data(getJson("/trading-calendar/status", token,
                    "marketCode", "CN", "date", "2098-01-02"));
            assertTrue(day.path("isTradingDay").asBoolean());
            JsonNode payload = latestAudit("system.trading-calendar.batch", "SUCCESS");
            assertTrue(payload.path("summary").asString().contains("2098-01-02"));
            assertEquals("TRADING_CALENDAR", payload.path("moduleCode").asString());
            assertEquals("TRADING_CALENDAR", payload.path("objects").get(0).path("object").path("objectType").asString());
        } finally {
            clear(2098, 2098);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void invalidWorkbookCannotBeApplied() throws Exception {
        String token = loginAsAdmin();
        var file = new MockMultipartFile("file", "invalid.xlsx", "application/octet-stream", new byte[]{1, 2, 3});
        var preview = data(mockMvc.perform(multipart("/trading-calendar/import/preview").file(file)
                .param("mode", "SINGLE").param("marketCode", "CN").header("Authorization", bearer(token))));
        assertTrue(preview.path("fingerprint").isNull());
        assertFalse(preview.path("errors").isEmpty());
        body(mockMvc.perform(multipart("/trading-calendar/import/apply").file(file)
                .param("mode", "SINGLE").param("marketCode", "CN").header("Authorization", bearer(token))), 400);
        JsonNode rejected = latestAudit("system.trading-calendar.import", "FAILURE");
        assertEquals("BAD_REQUEST", rejected.path("errorCode").asString());
        assertEquals("TRADING_CALENDAR", rejected.path("objects").get(0).path("object").path("objectType").asString());
    }

    @Test
    void listsAnAppliedDateRangeAndRequiresItsPreview() throws Exception {
        String token = loginAsAdmin();
        String path = "/trading-calendar";
        var range = map("marketCodes", List.of("CN"), "startDate", "2094-12-30", "endDate", "2094-12-31",
                "isTradingDay", true, "holidayMode", "KEEP");
        body(postJson(path + "/batch/apply", token, range), 400);
        assertEquals(0, rows(2094));
        apply(token, "batch", range);
        var days = data(getJson(path + "/year", token, "year", 2094, "marketCodes", "CN"));
        assertEquals(List.of("2094-12-30", "2094-12-31"),
                days.valueStream().map(day -> day.path("calendarDate").asString()).toList());
        assertEquals("2094-12-31", data(offset(token, "2094-12-31", 0)).path("date").asString());
        body(getJson(path + "/year", token, "year", 999, "marketCodes", "CN"), 400);
        body(getJson(path + "/year", token, "year", 2094, "marketCodes", "UNKNOWN"), 400);
        body(getJson(path + "/status", token, "date", "0999-01-01", "marketCode", "CN"), 400);
    }

    @Test
    void rejectsIncompleteInvertedOrOversizedRangesAndEmptyHolidayNames() throws Exception {
        String token = loginAsAdmin();
        String path = "/trading-calendar/batch/preview";
        var request = map("marketCodes", List.of("CN"), "isTradingDay", false);
        body(postJson(path, token, request), 400);
        request.put("startDate", "2094-01-02");
        body(postJson(path, token, request), 400);
        request.put("endDate", "2094-01-01");
        body(postJson(path, token, request), 400);
        request.put("endDate", "2110-01-01");
        body(postJson(path, token, request), 400);
        request.put("endDate", "2094-01-03");
        request.put("holidayMode", "SET");
        request.put("holidayName", " ");
        body(postJson(path, token, request), 400);
        assertEquals(0, rows(2094));
    }

    @Test
    void recurrenceStopsAtAnExplicitEndButReportsAnUnboundedSearchLimit() throws Exception {
        var market = repository.markets().stream().filter(item -> item.market().code().equals("CN")).findFirst().orElseThrow();
        var end = LocalDate.of(9999, 12, 31);
        var base = end.minusDays(1);
        repository.apply(List.of(new Delta(market, base, null, new Values(true, null, Source.MANUAL, null)),
                new Delta(market, end, null, new Values(false, null, Source.MANUAL, null))), 1L);

        assertNull(calendar.nextRecurrenceBase("CN", base, 1, end));
        assertThatThrownBy(() -> calendar.nextRecurrenceBase("CN", base, 1, null))
                .isInstanceOf(BadRequestException.class).hasMessage("error.trading-calendar.limit");
        assertThatThrownBy(() -> calendar.nextRecurrenceBase("CN", base, 0, end))
                .isInstanceOf(BadRequestException.class).hasMessage("error.trading-calendar.range");
        assertThatThrownBy(() -> calendar.nextRecurrenceBase("CN", base, 367, end))
                .isInstanceOf(BadRequestException.class).hasMessage("error.trading-calendar.limit");
        code(offset(loginAsAdmin(), end.toString(), 1), 12001);
    }

    @Test
    void importTemplatesMatchTheRequestedMode() throws Exception {
        String token = loginAsAdmin();
        for (var mode : List.of("SINGLE", "MULTI")) {
            var response = getJson("/trading-calendar/import/template", token, "mode", mode)
                    .andReturn().getResponse();
            assertEquals(200, response.getStatus());
            assertTrue(response.getHeader("Content-Disposition").contains(mode.toLowerCase(java.util.Locale.ROOT) + ".xlsx"));
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
                int expected = "MULTI".equals(mode) ? 5 : 4;
                assertEquals(expected, workbook.getSheetAt(0).getRow(0).getPhysicalNumberOfCells());
            }
        }
    }

    private JsonNode latestAudit(String operation, String result) {
        return jdbc.query("""
                        SELECT payload FROM sys_audit_outbox WHERE operationCode = ? AND result = ? ORDER BY id DESC LIMIT 1
                        """, (rs, row) -> objectMapper.readTree(rs.getString(1)), operation, result)
                .stream().findFirst().orElseThrow(() -> new AssertionError("Missing " + result + " event for " + operation));
    }

    private void apply(String token, String operation, Map<String, Object> request) throws Exception {
        var prefix = "/trading-calendar/" + operation;
        var preview = data(postJson(prefix + "/preview", token, request));
        assertTrue(preview.path("errors").isEmpty());
        request.put("fingerprint", preview.path("fingerprint").asString());
        data(postJson(prefix + "/apply", token, request));
    }

    private Map<String, Object> batch(String date, boolean trading, String holiday) {
        return map("marketCodes", List.of("CN"), "dates", List.of(date), "isTradingDay", trading,
                "holidayMode", holiday == null ? "KEEP" : "SET", "holidayName", holiday);
    }

    private ResultActions offset(String token, String date, int n) throws Exception {
        return getJson("/trading-calendar/offset", token, "marketCode", "CN", "date", date, "n", n);
    }

    private int rows(int year) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_trading_calendar_day WHERE YEAR(calendarDate) = ?", Integer.class, year);
    }

    private void clear(int fromYear, int toYear) {
        jdbc.update("DELETE FROM sys_trading_calendar_day WHERE YEAR(calendarDate) BETWEEN ? AND ?", fromYear, toYear);
    }
}
