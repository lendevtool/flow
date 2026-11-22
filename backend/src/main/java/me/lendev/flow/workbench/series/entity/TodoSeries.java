package me.lendev.flow.workbench.series.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import me.lendev.flow.workbench.series.SeriesModels.BlockReason;
import me.lendev.flow.workbench.series.SeriesModels.Status;
import me.lendev.flow.workbench.series.SeriesRuleDates.Frequency;
import me.lendev.flow.workbench.series.SeriesRuleDates.MonthDayMode;

/**
 * 待办周期规则与生成游标的持久化实体。
 */
@Getter
@Setter
@Table(value = "wb_todo_series", camelToUnderline = false)
public class TodoSeries {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Integer ownerUserId;
    private String ownerName;
    private Integer assigneeUserId;
    private String title;
    private String description;
    private String priority;
    private Frequency frequency;
    private Integer intervalValue;
    /**
     * 按位表示选中的星期，bit0 为周一，仅 WEEKLY 频率使用。
     */
    private Integer weekdaysMask;
    private MonthDayMode monthDayMode;
    private LocalDate anchorDate;
    private LocalDate untilDate;
    private Integer durationDays;
    private Integer dueOffsetDays;
    private Long marketItemId;
    private Integer startTradeOffset;
    private Integer endTradeOffset;
    private Integer dueTradeOffset;
    /**
     * 公共维度槽位 1-5，指向参数项 ID，未设置为 null。
     */
    private Long dimension1ItemId;
    private Long dimension2ItemId;
    private Long dimension3ItemId;
    private Long dimension4ItemId;
    private Long dimension5ItemId;
    private Status status;
    /**
     * 规则内容版本号，每次编辑递增，用于实例溯源。
     */
    private Integer ruleRevision;
    /**
     * 已处理的最后一个锚点。
     */
    private LocalDate lastBaseDate;
    /**
     * 待生成的下一个锚点，规则结束时为 null。
     */
    private LocalDate nextBaseDate;
    /**
     * 当前规则版本的生效业务日期。
     */
    private LocalDate effectiveFromDate;
    /**
     * 下次调度检查时间（UTC），生成受阻时延后。
     */
    private LocalDateTime nextCheckAt;
    private LocalDate blockedDate;
    private BlockReason blockReason;
    /**
     * 乐观锁版本，规则或游标变化时递增。
     */
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
