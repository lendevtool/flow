package me.lendev.flow.workbench.todo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工作台待办的写入、查询与展示契约。
 */
public final class TodoModels {

    private TodoModels() {
    }

    /**
     * 待办来源：手动创建、业务模块、周期规则、流程节点。
     */
    public enum Origin {MANUAL, BUSINESS, SERIES, FLOW}

    /**
     * 待办状态，DONE 与 CANCELLED 为终态。
     */
    public enum Status {
        OPEN, IN_PROGRESS, DONE, CANCELLED;

        /**
         * 是否为关闭终态，终态待办不可再操作。
         */
        public boolean closed() {
            return this == DONE || this == CANCELLED;
        }
    }

    public enum Priority {LOW, NORMAL, HIGH}

    /**
     * 排期方式：无排期、固定自然日、相对交易日。
     */
    public enum ScheduleMode {NONE, FIXED, TRADING}

    /**
     * 排期日期三元组，开始与结束须成对出现。
     */
    public record Dates(LocalDate startDate, LocalDate endDate, LocalDate dueDate) {

    }

    /**
     * 交易排期预览结果，coverageStart/End 为本次解析验证过的日历连续覆盖区间，
     * 提交时原样回传以确认日历未变化。
     */
    public record SchedulePreview(LocalDate startDate, LocalDate endDate, LocalDate dueDate,
                                  LocalDate coverageStart, LocalDate coverageEnd) {

    }

    /**
     * 用户 ID 与姓名快照。
     */
    public record Person(int id, String name) {

    }

    /**
     * 待办写入请求。requestKey 为幂等键；FIXED 使用自然日日期，
     * TRADING 使用市场与交易日偏移；version 编辑时必填；
     * personalDimensions 仅接收不输出，业务来源调用方不可携带。
     */
    public record Write(String requestKey, String title, String description, Integer assigneeUserId, String priority,
                        String scheduleMode, LocalDate startDate, LocalDate endDate, LocalDate dueDate,
                        String marketCode, LocalDate tradeBaseDate, Integer startTradeOffset, Integer endTradeOffset,
                        Integer dueTradeOffset, Map<Integer, Long> dimensions, Dates preview, Long version,
                        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) Map<Integer, String> personalDimensions) {

        /**
         * 供业务、周期和流程调用方使用，不接收私人标记。
         */
        public Write(String requestKey, String title, String description, Integer assigneeUserId, String priority,
                     String scheduleMode, LocalDate startDate, LocalDate endDate, LocalDate dueDate,
                     String marketCode, LocalDate tradeBaseDate, Integer startTradeOffset, Integer endTradeOffset,
                     Integer dueTradeOffset, Map<Integer, Long> dimensions, Dates preview, Long version) {
            this(requestKey, title, description, assigneeUserId, priority, scheduleMode, startDate, endDate, dueDate,
                    marketCode, tradeBaseDate, startTradeOffset, endTradeOffset, dueTradeOffset, dimensions,
                    preview, version, null);
        }
    }

    /**
     * 待办动作请求。version 必填用于乐观锁；cancel 须填 reason；assign 须填 assigneeUserId。
     */
    public record Action(Long version, String reason, Integer assigneeUserId) {

    }

    /**
     * 待办完整视图。requestKey 不对外输出；allowedActions/sourceLabel/sourceUrl
     * 按当前查看人装饰生成。
     */
    public record Todo(long id, String title, String description, Origin origin, Priority priority, Status status,
                       Integer ownerUserId, String ownerName, Long departmentId, int assigneeUserId,
                       String assigneeName,
                       String sourceModule, String businessType, String businessId,
                       @com.fasterxml.jackson.annotation.JsonIgnore String requestKey,
                       ScheduleMode scheduleMode, LocalDate startDate, LocalDate endDate, LocalDate dueDate,
                       String marketCode, LocalDate tradeBaseDate, Integer startTradeOffset, Integer endTradeOffset,
                       Integer dueTradeOffset, Map<Integer, Long> dimensions, Map<Integer, String> personalDimensions,
                       Long seriesId, long version, LocalDateTime createdAt, LocalDateTime closedAt,
                       List<String> allowedActions, String sourceLabel, String sourceUrl) {

        Todo forViewer(Map<Integer, String> personal, List<String> actions, String label, String url) {
            return new Todo(id, title, description, origin, priority, status, ownerUserId, ownerName, departmentId,
                    assigneeUserId, assigneeName, sourceModule, businessType, businessId, requestKey, scheduleMode,
                    startDate, endDate, dueDate, marketCode, tradeBaseDate, startTradeOffset, endTradeOffset,
                    dueTradeOffset, dimensions, personal, seriesId, version, createdAt, closedAt, actions, label, url);
        }

        Write toWrite() {
            boolean fixed = scheduleMode == ScheduleMode.FIXED;
            return new Write(null, title, description, assigneeUserId, priority.name(), scheduleMode.name(),
                    fixed ? startDate : null, fixed ? endDate : null, fixed ? dueDate : null, marketCode,
                    tradeBaseDate, startTradeOffset, endTradeOffset, dueTradeOffset, dimensions, null, version);
        }
    }

    /**
     * 分页结果，scopeCounts 为各范围（all/mine/created/done）总数。
     */
    public record TodoPage(List<Todo> records, long total, Map<String, Long> scopeCounts) {

    }

    /**
     * 日历视图条目，仅含排期所需字段。
     */
    public record CalendarTodo(long id, String title, Status status, LocalDate startDate, LocalDate endDate,
                               LocalDate dueDate) {

    }

    /**
     * 维度分组统计行，key 为 null 表示未填写，name 为展示名。
     */
    public record Statistic(String key, String name, long total, long open, long done, long overdue) {

    }

    /**
     * 个人维度选项，key 为稳定 UUID，停用后仍可用于展示历史标记。
     */
    public record PersonalOption(String key, String name, boolean enabled) {

    }

    /**
     * 个人维度配置写入请求，options 为空列表表示文本维度，version 为 null 或 0 表示新建。
     */
    public record PersonalWrite(String name, boolean enabled, Long version, List<PersonalOption> options) {

    }

    /**
     * 个人维度配置视图，options 为空表示文本维度。
     */
    public record PersonalDimension(int slot, String name, boolean enabled, long version,
                                    List<PersonalOption> options) {

    }

    /**
     * 业务来源标识。module 为登记模块，type/id 定位业务对象，ownerId 为可选发起人。
     */
    public record Source(String module, String type, String id, Integer ownerId) {

    }
}
