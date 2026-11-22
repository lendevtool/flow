package me.lendev.flow.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.StreamSupport;

import me.lendev.flow.openapi.OpenApiException;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class OpenTradingCalendarAdapterTest extends ApiTestBase {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlers;
    @Autowired
    private OpenTradingCalendarController controller;
    @Autowired
    private OpenTradingCalendarService calendar;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void mapsEachTradingCalendarReadRoute() {
        assertThat(handlers.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .filter(path -> path.startsWith("/open/v1/trading-calendar/")))
                .containsExactlyInAnyOrder(
                        "/open/v1/trading-calendar/markets", "/open/v1/trading-calendar/year",
                        "/open/v1/trading-calendar/status", "/open/v1/trading-calendar/offset");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void returnsAllActiveMarketsWithThePublishedFields() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var result = body(mvc.perform(get("/open/v1/trading-calendar/markets")));
        var markets = StreamSupport.stream(result.spliterator(), false).toList();
        assertThat(markets).extracting(row -> row.get("code").asString()).contains("CN", "HK");
        assertThat(markets.stream().filter(row -> "CN".equals(row.get("code").asString())).findFirst().orElseThrow())
                .isEqualTo(objectMapper.readTree("""
                        {"code":"CN","name":"中国内地","nameEn":"Mainland China","timeZone":"Asia/Shanghai"}
                        """));
    }

    @Test
    void queriesAllRequestedActiveMarketsUsingNormalizedCodes() {
        var date = LocalDate.of(2040, 1, 1);
        insertTradingDay("CN", date);
        insertTradingDay("HK", date);
        var markets = List.of(" cn ", "hK");
        assertThat(calendar.year(2040, markets)).extracting(OpenTradingCalendarModels.Day::marketCode)
                .containsExactlyInAnyOrder("CN", "HK");
        assertThat(calendar.readYear(2040, markets)).extracting(TradingCalendarModels.Day::marketCode)
                .containsExactlyInAnyOrder("CN", "HK");
    }

    @Test
    void rejectsTheWholeYearQueryWhenOneMarketIsUnknown() {
        var markets = List.of(" cn ", "UNKNOWN");
        assertThatThrownBy(() -> calendar.year(2040, markets))
                .isInstanceOf(OpenApiException.class).extracting("code").isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void appliesMarketAvailabilityToOpenReads() {
        insertMarket("API_DISABLED");
        jdbc.update("UPDATE sys_param_item SET enable = FALSE WHERE id = ?", marketId("API_DISABLED"));
        assertThat(calendar.markets()).extracting(OpenTradingCalendarModels.Market::code).doesNotContain("API_DISABLED");
        var date = LocalDate.of(2040, 1, 1);
        assertThatThrownBy(() -> calendar.status("API_DISABLED", date))
                .isInstanceOf(OpenApiException.class).extracting("code").isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void preservesCalendarCoverageAndTheClosedBaseError() throws Exception {
        var date = LocalDate.of(2040, 1, 1);
        jdbc.update("""
                INSERT INTO sys_trading_calendar_day
                    (marketItemId, calendarDate, calendarDate8, isTradingDay, holidayName, source)
                VALUES (?, ?, '20400101', FALSE, 'New Year', 'MANUAL')
                """, marketId("CN"), date);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var response = body(mvc.perform(get("/open/v1/trading-calendar/status")
                .param("marketCode", " cn ").param("date", "2040-01-01")));
        assertThat(response).isEqualTo(objectMapper.readTree("""
                {"marketCode":"CN","calendarDate":"2040-01-01","isTradingDay":false,"holidayName":"New Year"}
                """));
        var missingDate = date.plusDays(1);
        assertThatThrownBy(() -> calendar.status("CN", missingDate))
                .isInstanceOf(OpenApiException.class).extracting("code").isEqualTo("CALENDAR_COVERAGE_MISSING");
        assertThatThrownBy(() -> calendar.offset("CN", date, 0))
                .isInstanceOf(OpenApiException.class).extracting("code").isEqualTo("CALENDAR_BASE_CLOSED");
        assertThatThrownBy(() -> calendar.offset("CN", date, Integer.MIN_VALUE))
                .isInstanceOf(OpenApiException.class).extracting("code").isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void rejectsDatesWithTimeZoneOffsets() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/open/v1/trading-calendar/status")
                        .param("marketCode", "CN").param("date", "2040-01-01+08:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void queriesYearAndOffsetsThroughThePublishedRequestContracts() throws Exception {
        var date = LocalDate.of(2040, 2, 1);
        insertTradingDay("CN", date);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var year = body(mvc.perform(get("/open/v1/trading-calendar/year")
                .param("year", "2040").param("marketCodes", "CN")));
        assertThat(year.get(0).get("calendarDate").asString()).isEqualTo("2040-02-01");
        var offset = body(mvc.perform(get("/open/v1/trading-calendar/offset")
                .param("marketCode", "CN")
                .param("date", "2040-02-01").param("n", "0")));
        assertThat(offset.get("date").asString()).isEqualTo("2040-02-01");
    }

    private long marketId(String code) {
        return jdbc.queryForObject("""
                SELECT i.id FROM sys_param_item i
                JOIN sys_param_group g ON g.id = i.groupId
                WHERE g.code = 'TRADING_MARKET' AND i.code = ?
                """, Long.class, code);
    }

    private void insertTradingDay(String marketCode, LocalDate date) {
        jdbc.update("""
                INSERT INTO sys_trading_calendar_day (marketItemId, calendarDate, calendarDate8, isTradingDay, source)
                VALUES (?, ?, ?, TRUE, 'MANUAL')
                """, marketId(marketCode), date, date.format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    private void insertMarket(String code) {
        jdbc.update("""
                INSERT INTO sys_param_item (groupId, code, name, nameEn, value1, value2)
                SELECT id, ?, '测试市场', 'Test Market', 'Etc/UTC', 'test'
                FROM sys_param_group WHERE code = 'TRADING_MARKET'
                """, code);
    }
}
