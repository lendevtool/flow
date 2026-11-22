package me.lendev.flow.calendar;

import static me.lendev.flow.calendar.TradingCalendarController.*;

import java.util.Map;
import java.util.Set;

import me.lendev.flow.calendar.TradingCalendarModels.Day;
import me.lendev.flow.calendar.TradingCalendarModels.Market;
import me.lendev.flow.calendar.TradingCalendarModels.Offset;
import me.lendev.flow.openapi.ManagedReadPolicy;
import org.springframework.stereotype.Component;

/**
 * 声明交易日历中支持应用身份的只读路由及可配置公开字段。
 */
@Component
class TradingCalendarReadPolicy implements ManagedReadPolicy {
    @Override
    public Class<?> controllerType() {
        return TradingCalendarController.class;
    }

    @Override
    public String moduleCode() {
        return "trading-calendar";
    }

    @Override
    public String dataScopeType() {
        return "MARKET";
    }

    @Override
    public String scopePolicyVersion() {
        return "trading-calendar-market-v1";
    }

    @Override
    public Map<Class<?>, Set<String>> responseFields() {
        return Map.of(
                Market.class, Set.of("code", "name", "nameEn", "timeZone"),
                Day.class, Set.of("marketCode", "calendarDate", "isTradingDay", "holidayName"),
                Offset.class, Set.of("date"));
    }

    @Override
    public Set<String> sourcePaths() {
        return Set.of(ROOT_PATH + MARKETS_PATH, ROOT_PATH + YEAR_PATH, ROOT_PATH + STATUS_PATH, ROOT_PATH + OFFSET_PATH);
    }
}
