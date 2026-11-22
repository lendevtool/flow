package me.lendev.flow.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import me.lendev.flow.support.ApiTestBase;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

class TradingCalendarImportApiTest extends ApiTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TradingCalendarExcel excel;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void multiMarketImportAndSingleMarketOverwriteCommitWithAudit() throws Exception {
        String token = loginAsAdmin();
        String date = "2094-10-01";
        try {
            var multi = upload("MULTI", List.of(
                    List.of("CN", "20941001", "0", "国庆节", "公告"),
                    List.of("JP", date, "1", "", "")));
            var preview = data(importRequest(token, "preview", multi, "MULTI", null, null));
            assertEquals(2, preview.path("added").asInt());
            assertEquals(0, count(date));
            var applied = data(importRequest(token, "apply", multi, "MULTI", null,
                    preview.path("fingerprint").asString()));
            assertEquals(2, applied.path("added").asInt());
            assertEquals(2, count(date));
            assertEquals("国庆节", day(token, "CN", date).path("holidayName").asString());
            assertFalse(day(token, "CN", date).path("isTradingDay").asBoolean());
            assertTrue(day(token, "JP", date).path("isTradingDay").asBoolean());

            var single = upload("SINGLE", List.of(List.of(date, "0", "纪念日", "已调整")));
            var singlePreview = data(importRequest(token, "preview", single, "SINGLE", "JP", null));
            assertEquals(1, singlePreview.path("changed").asInt());
            data(importRequest(token, "apply", single, "SINGLE", "JP",
                    singlePreview.path("fingerprint").asString()));
            var changed = day(token, "JP", date);
            assertEquals("EXCEL", changed.path("source").asString());
            assertEquals("已调整", changed.path("remark").asString());
            assertEquals("纪念日", changed.path("holidayName").asString());
            assertFalse(changed.path("isTradingDay").asBoolean());
            assertEquals("国庆节", day(token, "CN", date).path("holidayName").asString());

            String audited = jdbc.queryForObject("""
                    SELECT payload FROM sys_audit_outbox WHERE operationCode = 'system.trading-calendar.import'
                    AND result = 'SUCCESS' ORDER BY id DESC LIMIT 1
                    """, String.class);
            assertTrue(objectMapper.readTree(audited).path("summary").asString().contains(date));
        } finally {
            jdbc.update("DELETE FROM sys_trading_calendar_day WHERE calendarDate = CAST(? AS DATE)", date);
        }
    }

    @Test
    void mixedWorkbookReportsValidRowCountsButRejectsTheWholeImport() throws Exception {
        String token = loginAsAdmin();
        String date = "2094-10-02";
        var workbook = upload("MULTI", List.of(
                List.of("CN", date, "0", "国庆节", ""),
                List.of("UNCONFIGURED", date, "1", "", "")));
        var preview = data(importRequest(token, "preview", workbook, "MULTI", null, null));
        assertEquals(1, preview.path("added").asInt());
        assertTrue(preview.path("fingerprint").isNull());
        assertEquals(3, preview.path("errors").get(0).path("row").asInt());
        body(importRequest(token, "apply", workbook, "MULTI", null, null), 400);
        assertEquals(0, count(date));
    }

    private MockMultipartFile upload(String mode, List<List<String>> rows) throws Exception {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(excel.template(mode)));
             var bytes = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            for (int i = 0; i < rows.size(); i++) {
                var row = sheet.createRow(i + 1);
                for (int j = 0; j < rows.get(i).size(); j++) {
                    row.createCell(j).setCellValue(rows.get(i).get(j));
                }
            }
            workbook.write(bytes);
            return new MockMultipartFile("file", "trading-calendar.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray());
        }
    }

    private ResultActions importRequest(String token, String action, MockMultipartFile file, String mode,
                                        String marketCode, String fingerprint) throws Exception {
        var request = multipart("/trading-calendar/import/" + action).file(file).param("mode", mode)
                .header("Authorization", bearer(token));
        if (marketCode != null) {
            request.param("marketCode", marketCode);
        }
        if (fingerprint != null) {
            request.param("fingerprint", fingerprint);
        }
        return mockMvc.perform(request);
    }

    private JsonNode day(String token, String marketCode, String date) throws Exception {
        return data(getJson("/trading-calendar/status", token, "marketCode", marketCode, "date", date));
    }

    private int count(String date) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_trading_calendar_day WHERE calendarDate = CAST(? AS DATE)",
                Integer.class, date);
    }
}
