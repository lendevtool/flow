package me.lendev.flow.workbench.todo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import me.lendev.flow.workbench.todo.BusinessTodoCalendarDimension.Option;
import me.lendev.flow.workbench.todo.TodoModels.Status;

/**
 * 业务日历只读接口的返回对象。
 */
public final class BusinessTodoCalendarModels {

    private BusinessTodoCalendarModels() {
    }

    /**
     * 固定对象 ID 和名称。
     */
    public record ContextSummary(String id, String label) {
    }

    /**
     * 状态标签的查询值和名称。
     */
    public record StatusOption(String value, String label) {
    }

    /**
     * 用于渲染筛选控件的场景维度。
     */
    public record Dimension(String key, String label, boolean primary, boolean multiple, boolean searchable,
                            List<Option> options) {
    }

    /**
     * 入口标题、固定对象和默认筛选配置。
     */
    public record Meta(String title, ContextSummary context, String defaultView, List<StatusOption> statuses,
                       List<Dimension> dimensions) {
    }

    /**
     * 区间中的状态计数，全部指待办和已办。
     */
    public record Counts(long all, long pending, long done, long cancelled) {
    }

    /**
     * 已授权的任务排期摘要，日期为空代表没有相应排期。
     */
    public record Item(String todoId, String title, Status status, boolean overdue, LocalDate startDate,
                       LocalDate endDate, LocalDate dueDate, int assigneeUserId, String assigneeName,
                       String taskKindCode, String recordLabel) {
    }

    /**
     * 详情沿用排期摘要字段，并展示 UTC 关闭时间和可用的站内链接。
     */
    public record Detail(String todoId, String title, Status status, boolean overdue, LocalDate startDate,
                         LocalDate endDate, LocalDate dueDate, int assigneeUserId, String assigneeName,
                         String taskKindCode, String recordLabel, Instant closedAt, String businessUrl,
                         String workUrl) {
        static Detail from(Item item, Instant closedAt, String businessUrl, String workUrl) {
            return new Detail(item.todoId(), item.title(), item.status(), item.overdue(), item.startDate(),
                    item.endDate(), item.dueDate(), item.assigneeUserId(), item.assigneeName(), item.taskKindCode(),
                    item.recordLabel(), closedAt, businessUrl, workUrl);
        }
    }

    /**
     * 网格结果。超出容量时返回空条目、准确计数和分页切换标记。
     */
    public record Calendar(List<Item> items, Counts counts, long unscheduledCount, boolean overflow) {
    }

    /**
     * 已授权数据中的分页结果及同条件总数。
     */
    public record PageResult<T>(List<T> items, long total) {
    }
}
