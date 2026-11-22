package me.lendev.flow.calendar;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 交易日历的输入与响应契约；不暴露数据库主键及审计字段。
 */
public final class TradingCalendarModels {

    private TradingCalendarModels() {
    }

    public enum Source {INIT, EXCEL, MANUAL}

    public enum HolidayMode {KEEP, SET, CLEAR}

    public enum ImportMode {SINGLE, MULTI}

    public record Market(String code, String name, String nameEn, String timeZone, String iconKey) {

    }

    public record Day(String marketCode, LocalDate calendarDate, String calendarDate8, boolean isTradingDay,
                      String holidayName, Source source, String remark) {

    }

    public record Offset(LocalDate date) {

    }

    public record Initialize(int year, @NotEmpty @Size(max = 32) List<@NotBlank @Size(max = 64) String> marketCodes,
                             @Size(max = 64) String fingerprint) {

    }

    public record Batch(@NotEmpty @Size(max = 32) List<@NotBlank @Size(max = 64) String> marketCodes,
                        @Size(max = 3660) List<@NotNull LocalDate> dates, LocalDate startDate, LocalDate endDate,
                        @NotNull Boolean isTradingDay, HolidayMode holidayMode, @Size(max = 100) String holidayName,
                        @Size(max = 64) String fingerprint) {

    }

    public record Issue(int row, String message) {

    }

    public record Preview(int added, int changed, int unchanged, List<Issue> errors, String fingerprint) {

        public Preview {
            errors = List.copyOf(errors);
        }
    }
}
