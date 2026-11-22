package me.lendev.flow.workbench.todo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.Getter;
import lombok.Setter;
import me.lendev.flow.workbench.todo.TodoModels.Origin;
import me.lendev.flow.workbench.todo.TodoModels.Priority;
import me.lendev.flow.workbench.todo.TodoModels.ScheduleMode;
import me.lendev.flow.workbench.todo.TodoModels.Status;

/**
 * 待办实例的持久化实体。
 */
@Getter
@Setter
@Table(value = "wb_todo", camelToUnderline = false)
public class TodoInstance {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String title;
    private String description;
    private Origin origin;
    private Priority priority;
    private Status status;
    private Integer ownerUserId;
    private String ownerName;
    private Long departmentId;
    private Integer assigneeUserId;
    private String assigneeName;
    private String sourceModule;
    private String businessType;
    private String businessId;
    /**
     * 幂等作用域（USER:id / MODULE:module），与 requestKey 构成唯一约束。
     */
    private String requestScope;
    private String requestKey;
    /**
     * 创建请求内容摘要，幂等回放时比对。
     */
    private String requestHash;
    private ScheduleMode scheduleMode;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate dueDate;
    private Long marketItemId;
    private LocalDate tradeBaseDate;
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
    private Long seriesId;
    /**
     * 生成时的规则修订号，用于实例溯源。
     */
    private Integer seriesRevision;
    private LocalDate seriesBaseDate;
    /**
     * 期次业务发生日期，周期变更影响范围按此判断。
     */
    private LocalDate occurrenceDate;
    /**
     * 周期实例被单独编辑后标记，规则编辑时不再自动取消。
     */
    private Boolean occurrenceEdited;
    private LocalDateTime closedAt;
    private Integer closedBy;
    /**
     * 取消类型：MANUAL/FLOW_CANCEL/SERIES_STOP/SERIES_CHANGE 等。
     */
    private String cancelKind;
    private String cancelReason;
    /**
     * 乐观锁版本，每次写入递增。
     */
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
