package me.lendev.flow.workbench.series;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import me.lendev.flow.workbench.series.SeriesRuleDates.Frequency;
import me.lendev.flow.workbench.series.SeriesRuleDates.MonthDayMode;
import me.lendev.flow.workbench.todo.TodoModels.Dates;

/**
 * 周期规则、生成日期与变更预览的接口契约。
 */
public final class SeriesModels {

    private SeriesModels() {
    }

    /**
     * 规则状态：活跃生成中、已停止、锚点已全部处理完。
     */
    public enum Status {ACTIVE, STOPPED, FINISHED}

    /**
     * 规则变更动作：编辑内容或停止规则。
     */
    public enum ChangeAction {EDIT, STOP}

    /**
     * 生成阻塞原因：日历覆盖缺失、基准日休市、市场停用、负责人无效、锚点搜索超限。
     */
    public enum BlockReason {COVERAGE_MISSING, BASE_CLOSED, MARKET_DISABLED, ASSIGNEE_INVALID, SEARCH_LIMIT}

    /**
     * 规则写入请求。自然日周期使用 durationDays/dueOffsetDays，
     * 交易周期使用 marketCode 与交易日偏移，两组参数互斥。
     */
    public record Write(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 10000) String description,
            @Positive Integer assigneeUserId,
            @Size(max = 12) String priority,
            @NotNull Frequency frequency,
            @NotNull @Positive Integer intervalValue,
            Integer weekdaysMask,
            MonthDayMode monthDayMode,
            @NotNull LocalDate anchorDate,
            LocalDate untilDate,
            @Positive Integer durationDays,
            Integer dueOffsetDays,
            @Size(max = 64) String marketCode,
            Integer startTradeOffset,
            Integer endTradeOffset,
            Integer dueTradeOffset,
            @Size(max = 5) Map<Integer, Long> dimensions
    ) {

        boolean trading() {
            return frequency == Frequency.TRADING_DAILY;
        }
    }

    /**
     * 规则完整视图，规则内容平铺展开。version 为乐观锁版本，
     * lastBaseDate/nextBaseDate 为生成游标，blockedDate/blockReason 描述当前阻塞。
     */
    public record Rule(
            long id,
            int ownerUserId,
            String ownerName,
            @JsonUnwrapped Write rule,
            Status status,
            int ruleRevision,
            LocalDate lastBaseDate,
            LocalDate nextBaseDate,
            LocalDate effectiveFromDate,
            Instant nextCheckAt,
            LocalDate blockedDate,
            BlockReason blockReason,
            long version
    ) {

    }

    /**
     * 单期排期结果。occurrenceDate 取开始与截止日期的较早者，用于窗口与影响范围判断。
     */
    public record Occurrence(LocalDate seriesBaseDate, LocalDate occurrenceDate,
                             LocalDate startDate, LocalDate endDate, LocalDate dueDate) {

        static Occurrence of(LocalDate anchor, Dates dates) {
            LocalDate first = dates.startDate();
            if (first == null || dates.dueDate() != null && dates.dueDate().isBefore(first)) {
                first = dates.dueDate();
            }
            return new Occurrence(anchor, first, dates.startDate(), dates.endDate(), dates.dueDate());
        }

        Dates dates() {
            return new Dates(startDate, endDate, dueDate);
        }
    }

    /**
     * 排期预览。coverageStart/End 为交易日历覆盖范围，
     * blockedDate/blockReason 非空表示后续期次暂时无法计算。
     */
    public record Preview(List<Occurrence> occurrences, LocalDate coverageStart, LocalDate coverageEnd,
                          LocalDate blockedDate, BlockReason blockReason) {

    }

    /**
     * 受变更影响的实例摘要，version 用于确认令牌绑定实例版本。
     */
    public record Affected(long id, String title, LocalDate occurrenceDate, long version) {

    }

    /**
     * 变更预览。token 绑定规则版本、业务日期、请求内容与受影响实例版本，
     * cutoffDate 之后（不含当日）的开放实例将被取消。
     */
    public record ChangePreview(String token, LocalDate cutoffDate, List<Affected> affected, Preview schedule) {

    }

    /**
     * 变更预览请求，EDIT 必须携带新规则，STOP 时 rule 为空。
     */
    public record ChangeRequest(@NotNull ChangeAction action, @Valid Write rule) {

    }

    /**
     * 编辑提交请求，token 为变更预览返回的确认令牌。
     */
    public record EditRequest(@NotBlank @Size(max = 64) String token, @Valid @NotNull Write rule) {

    }

    /**
     * 停止提交请求，token 为变更预览返回的确认令牌。
     */
    public record StopRequest(@NotBlank @Size(max = 64) String token) {

    }
}
