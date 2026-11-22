package me.lendev.flow.calendar;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 开放 API v1 的交易日历响应契约。
 */
public final class OpenTradingCalendarModels {

    private OpenTradingCalendarModels() {
    }

    /**
     * 当前启用的市场及其日历时区。
     *
     * @param code     市场编码，用于日历查询
     * @param name     市场中文展示名称
     * @param nameEn   市场英文展示名称，可空
     * @param timeZone 市场自然日所采用的时区标识
     */
    public record Market(String code, String name, String nameEn, String timeZone) {

    }

    /**
     * 一个市场在指定日期的公开日历记录。
     *
     * @param marketCode   市场编码
     * @param calendarDate 自然日，JSON 格式为 {@code uuuu-MM-dd}
     * @param isTradingDay 是否为交易日，休市时为 {@code false}
     * @param holidayName  配置的假日名称，无假日名称时显式返回 {@code null}
     */
    public record Day(String marketCode, LocalDate calendarDate, boolean isTradingDay,
                      @JsonInclude(JsonInclude.Include.ALWAYS) String holidayName) {

    }

    /**
     * 交易日偏移查询结果。
     *
     * @param date 命中的交易日，JSON 格式为 {@code uuuu-MM-dd}
     */
    public record Offset(LocalDate date) {

    }
}
