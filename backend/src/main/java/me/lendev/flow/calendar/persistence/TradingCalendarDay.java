package me.lendev.flow.calendar.persistence;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import lombok.Data;
import me.lendev.flow.calendar.TradingCalendarModels.Source;

/**
 * 以市场和日期为联合主键的交易日记录。
 */
@Data
@Table(value = "sys_trading_calendar_day", camelToUnderline = false)
public class TradingCalendarDay {

    @Id
    private Long marketItemId;
    @Id
    private LocalDate calendarDate;
    private String calendarDate8;
    private Boolean isTradingDay;
    private String holidayName;
    private Source source;
    private String remark;
    private Long createdBy;
    private Long updatedBy;
    @Column(onUpdateValue = "CURRENT_TIMESTAMP(3)")
    private LocalDateTime updatedAt;
}
