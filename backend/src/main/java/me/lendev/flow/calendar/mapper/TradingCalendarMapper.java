package me.lendev.flow.calendar.mapper;

import static me.lendev.flow.calendar.persistence.table.TradingCalendarDayTableDef.TRADING_CALENDAR_DAY;
import static me.lendev.flow.param.domain.table.ParamGroupTableDef.PARAM_GROUP;
import static me.lendev.flow.param.domain.table.ParamItemTableDef.PARAM_ITEM;

import java.time.LocalDate;
import java.util.List;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import me.lendev.flow.calendar.persistence.TradingCalendarDay;
import me.lendev.flow.param.domain.ParamItem;

/**
 * 读取市场和交易日历，并在调用方事务内写入日历明细。
 */
public interface TradingCalendarMapper extends BaseMapper<TradingCalendarDay> {

    default List<ParamItem> markets(String code) {
        var query = marketItems().join(PARAM_GROUP).on(PARAM_ITEM.GROUP_ID.eq(PARAM_GROUP.ID))
                .and(PARAM_GROUP.CODE.eq(code)).and(PARAM_GROUP.ENABLE.eq(true))
                .orderBy(PARAM_ITEM.SORT.asc(), PARAM_ITEM.ID.asc());
        return selectListByQueryAs(query, ParamItem.class);
    }

    default Long lockMarketGroup(String code) {
        return selectObjectByQueryAs(QueryWrapper.create().select(PARAM_GROUP.ID).from(PARAM_GROUP)
                .where(PARAM_GROUP.CODE.eq(code)).and(PARAM_GROUP.ENABLE.eq(true)).forUpdate(), Long.class);
    }

    default List<ParamItem> lockMarketItems(long groupId) {
        var query = marketItems().and(PARAM_ITEM.GROUP_ID.eq(groupId)).orderBy(PARAM_ITEM.ID.asc()).forUpdate();
        return selectListByQueryAs(query, ParamItem.class);
    }

    default List<TradingCalendarDay> range(long marketId, LocalDate from, LocalDate to, boolean ascending) {
        return selectListByQuery(days(marketId).and(TRADING_CALENDAR_DAY.CALENDAR_DATE.between(from, to))
                .orderBy(TRADING_CALENDAR_DAY.CALENDAR_DATE, ascending));
    }

    default List<TradingCalendarDay> selected(long marketId, List<LocalDate> dates, boolean lock) {
        if (dates.isEmpty()) {
            return List.of();
        }
        var query = days(marketId).and(TRADING_CALENDAR_DAY.CALENDAR_DATE.in(dates))
                .orderBy(TRADING_CALENDAR_DAY.CALENDAR_DATE.asc());
        return selectListByQuery(lock ? query.forUpdate() : query);
    }

    private static QueryWrapper days(long marketId) {
        return QueryWrapper.create().select(TRADING_CALENDAR_DAY.CALENDAR_DATE, TRADING_CALENDAR_DAY.IS_TRADING_DAY,
                        TRADING_CALENDAR_DAY.HOLIDAY_NAME, TRADING_CALENDAR_DAY.SOURCE, TRADING_CALENDAR_DAY.REMARK,
                        TRADING_CALENDAR_DAY.UPDATED_AT)
                .where(TRADING_CALENDAR_DAY.MARKET_ITEM_ID.eq(marketId));
    }

    private static QueryWrapper marketItems() {
        return QueryWrapper.create().select(PARAM_ITEM.ID, PARAM_ITEM.CODE, PARAM_ITEM.NAME, PARAM_ITEM.NAME_EN,
                        PARAM_ITEM.VALUE1, PARAM_ITEM.VALUE2)
                .from(PARAM_ITEM).where(PARAM_ITEM.ENABLE.eq(true));
    }
}
