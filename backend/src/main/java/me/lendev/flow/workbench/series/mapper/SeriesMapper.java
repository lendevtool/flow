package me.lendev.flow.workbench.series.mapper;

import static com.mybatisflex.core.query.QueryMethods.column;
import static com.mybatisflex.core.query.QueryMethods.max;
import static com.mybatisflex.core.query.QueryMethods.min;
import static com.mybatisflex.core.query.QueryMethods.upper;
import static me.lendev.flow.param.domain.table.ParamGroupTableDef.PARAM_GROUP;
import static me.lendev.flow.param.domain.table.ParamItemTableDef.PARAM_ITEM;
import static me.lendev.flow.workbench.series.entity.table.TodoSeriesTableDef.TODO_SERIES;
import static me.lendev.flow.workbench.todo.entity.table.TodoInstanceTableDef.TODO_INSTANCE;

import com.mybatisflex.annotation.TableRef;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryTable;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import me.lendev.flow.workbench.series.SeriesModels.Affected;
import me.lendev.flow.workbench.series.SeriesModels.Status;
import me.lendev.flow.workbench.series.entity.TodoSeries;
import me.lendev.flow.workbench.todo.TodoModels;
import me.lendev.flow.workbench.todo.entity.TodoInstance;

/**
 * 周期规则、生成游标及受影响实例的持久化映射。
 */
public interface SeriesMapper extends BaseMapper<TodoSeries> {

    /**
     * 按规则 ID 倒序查询并联表补充市场编码，owner 为 null 时返回全部。
     */
    default List<RuleRow> list(Integer owner) {
        return selectListByQueryAs(rules().where(TODO_SERIES.OWNER_USER_ID.eq(owner, owner != null))
                .orderBy(TODO_SERIES.ID.desc()), RuleRow.class);
    }

    /**
     * 锁定周期规则时通过标量子查询读取市场编码，锁范围限于规则行。
     */
    default RuleRow find(long id, boolean lock) {
        var query = rules().where(TODO_SERIES.ID.eq(id));
        if (lock) {
            query.forUpdate();
        }
        return selectOneByQueryAs(query, RuleRow.class);
    }

    /**
     * 选取检查时间已到期的活跃规则 ID，按检查时间与 ID 升序，最多 limit 条。
     */
    default List<Long> due(LocalDateTime now, int limit) {
        return selectObjectListByQueryAs(QueryWrapper.create().select(TODO_SERIES.ID).from(TODO_SERIES)
                .where(TODO_SERIES.STATUS.eq(Status.ACTIVE)).and(TODO_SERIES.NEXT_CHECK_AT.le(now))
                .orderBy(TODO_SERIES.NEXT_CHECK_AT.asc(), TODO_SERIES.ID.asc()).limit(limit), Long.class);
    }

    /**
     * 查询编码匹配且组与项均启用的市场参数项 ID，编码忽略大小写。
     */
    default List<Long> activeMarketIds(String code) {
        return selectObjectListByQueryAs(QueryWrapper.create().select(PARAM_ITEM.ID).from(PARAM_ITEM)
                .innerJoin(PARAM_GROUP).on(PARAM_GROUP.ID.eq(PARAM_ITEM.GROUP_ID))
                .where(PARAM_GROUP.CODE.eq("TRADING_MARKET")).and(PARAM_GROUP.ENABLE.eq(true))
                .and(PARAM_ITEM.ENABLE.eq(true)).and(upper(PARAM_ITEM.CODE).eq(code, true)), Long.class);
    }

    /**
     * 聚合市场交易日历的最小与最大日期，无数据时返回的行两个字段均为 null。
     */
    default CoverageRow coverage(long market) {
        var calendar = new QueryTable("sys_trading_calendar_day");
        var date = new QueryColumn(calendar, "calendarDate");
        var marketId = new QueryColumn(calendar, "marketItemId");
        return selectOneByQueryAs(QueryWrapper.create().select(min(date).as("fromDate"), max(date).as("toDate"))
                .from(calendar).where(marketId.eq(market)), CoverageRow.class);
    }

    /**
     * 条件延期：仅当规则仍活跃且检查时间不晚于 now 时更新，避免覆盖并发推进。
     */
    default void defer(long id, LocalDateTime now, LocalDateTime retry) {
        UpdateChain.of(this).set(TODO_SERIES.NEXT_CHECK_AT, retry)
                .where(TODO_SERIES.ID.eq(id)).and(TODO_SERIES.STATUS.eq(Status.ACTIVE))
                .and(TODO_SERIES.NEXT_CHECK_AT.le(now)).update();
    }

    /**
     * 按待办 ID 顺序取得或锁定生效日期之后的开放实例。
     * stop 为 false（编辑）时排除已单独编辑的实例，停止时全部包含。
     */
    default List<Affected> affected(long id, LocalDate cutoff, boolean stop, boolean lock) {
        var query = QueryWrapper.create().select(TODO_INSTANCE.ID, TODO_INSTANCE.TITLE, TODO_INSTANCE.OCCURRENCE_DATE,
                        TODO_INSTANCE.VERSION).from(TODO_INSTANCE)
                .where(TODO_INSTANCE.SERIES_ID.eq(id)).and(TODO_INSTANCE.OCCURRENCE_DATE.gt(cutoff))
                .and(TODO_INSTANCE.STATUS.eq(TodoModels.Status.OPEN))
                .and(TODO_INSTANCE.OCCURRENCE_EDITED.eq(false, !stop)).orderBy(TODO_INSTANCE.ID.asc());
        if (lock) {
            query.forUpdate();
        }
        return selectListByQueryAs(query, TodoInstance.class).stream().map(row ->
                new Affected(row.getId(), row.getTitle(), row.getOccurrenceDate(), row.getVersion())).toList();
    }

    /**
     * 查询锚点是否已有保留实例（取消类型为空或非 SERIES_CHANGE），存在返回实例 ID，否则 null。
     */
    default Long retainedOccurrence(long id, LocalDate anchor) {
        return selectObjectByQueryAs(QueryWrapper.create().select(TODO_INSTANCE.ID).from(TODO_INSTANCE)
                .where(TODO_INSTANCE.SERIES_ID.eq(id)).and(TODO_INSTANCE.SERIES_BASE_DATE.eq(anchor))
                .and((QueryWrapper q) -> {
                    q.where(TODO_INSTANCE.CANCEL_KIND.isNull()).or(TODO_INSTANCE.CANCEL_KIND.ne("SERIES_CHANGE"));
                }).limit(1), Long.class);
    }

    private static QueryWrapper rules() {
        var marketCode = QueryWrapper.create().select(PARAM_ITEM.CODE).from(PARAM_ITEM)
                .where(PARAM_ITEM.ID.eq(TODO_SERIES.MARKET_ITEM_ID));
        return QueryWrapper.create().select(TODO_SERIES.ALL_COLUMNS, column(marketCode).as("marketCode"))
                .from(TODO_SERIES);
    }

    /**
     * 规则行附带联表查询出的市场编码。
     */
    @Getter
    @Setter
    @TableRef(TodoSeries.class)
    class RuleRow extends TodoSeries {

        private String marketCode;
    }

    /**
     * 交易日历覆盖范围聚合结果。
     */
    @Getter
    @Setter
    class CoverageRow {

        private LocalDate fromDate;
        private LocalDate toDate;
    }
}
