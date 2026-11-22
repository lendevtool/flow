package me.lendev.flow.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.calendar.OpenTradingCalendarModels.Day;
import me.lendev.flow.calendar.OpenTradingCalendarModels.Market;
import me.lendev.flow.calendar.OpenTradingCalendarModels.Offset;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.exception.BizException;
import me.lendev.flow.openapi.OpenApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 复用交易日历的业务查询并转换开放接口响应。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class OpenTradingCalendarService {

    private final TradingCalendarService calendar;

    /**
     * 返回当前启用的市场，保留开放接口的市场字段。
     *
     * @return 市场参数顺序下的市场列表，无有效市场时为空
     */
    public List<Market> markets() {
        return calendar.markets().stream()
                .map(market -> new Market(market.code(), market.name(), market.nameEn(), market.timeZone()))
                .toList();
    }

    /**
     * 查询指定市场已配置的年度记录，未配置的日期省略。
     *
     * @param year        自然年，范围为 1000 至 9999
     * @param marketCodes 要查询的启用市场编码，重复编码合并
     * @return 按市场参数项 ID 和日期升序排列的公开记录
     * @throws OpenApiException 年份或市场编码不合法
     */
    public List<Day> year(int year, List<String> marketCodes) {
        return readYear(year, marketCodes).stream().map(OpenTradingCalendarService::externalDay).toList();
    }

    /**
     * 查询指定市场和日期的交易状态。
     *
     * @param code 启用的市场编码
     * @param date 市场自然日
     * @return 已覆盖日期的公开日历记录
     * @throws OpenApiException 参数无效或日期缺少日历覆盖
     */
    public Day status(String code, LocalDate date) {
        return externalDay(readStatus(code, date));
    }

    /**
     * 按已有的交易日历规则计算指定市场的交易日偏移。
     *
     * @param code 启用的市场编码
     * @param date 已覆盖的基准日期
     * @param n    交易日偏移量，范围为 -366 至 366
     * @return 偏移后命中的交易日
     * @throws OpenApiException 参数非法、覆盖缺失或零偏移的基准日休市
     */
    public Offset offset(String code, LocalDate date, int n) {
        return new Offset(readOffset(code, date, n).date());
    }

    List<TradingCalendarModels.Day> readYear(int year, List<String> marketCodes) {
        return read(() -> calendar.year(year, marketCodes));
    }

    TradingCalendarModels.Day readStatus(String code, LocalDate date) {
        return read(() -> calendar.status(code, date));
    }

    /**
     * 在开放接口允许的步数内计算交易日偏移。
     */
    TradingCalendarModels.Offset readOffset(String code, LocalDate date, int n) {
        if (Math.abs((long) n) > TradingCalendarService.MAX_SCHEDULE_OFFSET) {
            throw new OpenApiException(400, "INVALID_ARGUMENT", "Trading day offset must be between -366 and 366");
        }
        return read(() -> calendar.offset(code, date, n));
    }

    private static Day externalDay(TradingCalendarModels.Day day) {
        return new Day(day.marketCode(), day.calendarDate(), day.isTradingDay(), day.holidayName());
    }

    private static <T> T read(Supplier<T> query) {
        try {
            return query.get();
        } catch (BadRequestException e) {
            throw new OpenApiException(400, "INVALID_ARGUMENT", "Invalid trading calendar query");
        } catch (BizException e) {
            throw switch (e.getCode()) {
                case TRADING_CALENDAR_COVERAGE_MISSING ->
                        new OpenApiException(404, "CALENDAR_COVERAGE_MISSING", "Trading calendar coverage is missing");
                case TRADING_CALENDAR_BASE_CLOSED ->
                        new OpenApiException(400, "CALENDAR_BASE_CLOSED", "Base date is not a trading day");
                default -> e;
            };
        }
    }
}
