package me.lendev.flow.calendar;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.util.UpdateEntity;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.calendar.TradingCalendarModels.Market;
import me.lendev.flow.calendar.TradingCalendarModels.Source;
import me.lendev.flow.calendar.mapper.TradingCalendarMapper;
import me.lendev.flow.calendar.persistence.TradingCalendarDay;
import me.lendev.flow.param.domain.ParamItem;
import org.springframework.stereotype.Repository;

/**
 * 查询已启用的市场及日历明细，批量持久化经过预览校验的实际变更。
 */
@Repository
@RequiredArgsConstructor
class TradingCalendarRepository {

    private static final String MARKET_GROUP = "TRADING_MARKET";
    private static final int BATCH_SIZE = 500;

    private final TradingCalendarMapper mapper;

    record MarketItem(long id, Market market) {

    }

    record Values(boolean trading, String holiday, Source source, String remark) {

    }

    record StoredDay(LocalDate date, Values values, LocalDateTime updatedAt) {

    }

    record Delta(MarketItem market, LocalDate date, StoredDay before, Values after) {

        boolean modified() {
            return before == null || !before.values().equals(after);
        }
    }

    List<MarketItem> markets() {
        return mapper.markets(MARKET_GROUP).stream().map(TradingCalendarRepository::market).toList();
    }

    /**
     * 先锁参数分类，再按主键顺序锁明细。日历写入与参数维护遵守同一锁顺序。
     */
    List<MarketItem> lockMarkets() {
        Long groupId = mapper.lockMarketGroup(MARKET_GROUP);
        return groupId == null ? List.of()
                : mapper.lockMarketItems(groupId).stream().map(TradingCalendarRepository::market).toList();
    }

    List<StoredDay> range(long marketId, LocalDate from, LocalDate to, boolean ascending) {
        return mapper.range(marketId, from, to, ascending).stream().map(TradingCalendarRepository::day).toList();
    }

    List<StoredDay> selected(long marketId, List<LocalDate> dates, boolean lock) {
        List<StoredDay> rows = new ArrayList<>();
        for (int start = 0; start < dates.size(); start += BATCH_SIZE) {
            var batch = dates.subList(start, Math.min(start + BATCH_SIZE, dates.size()));
            mapper.selected(marketId, batch, lock).stream().map(TradingCalendarRepository::day).forEach(rows::add);
        }
        return rows;
    }

    void apply(List<Delta> deltas, long actor) {
        List<TradingCalendarDay> inserts = new ArrayList<>();
        List<TradingCalendarDay> updates = new ArrayList<>();
        for (Delta delta : deltas) {
            if (!delta.modified()) {
                continue;
            }
            Values value = delta.after();
            boolean insert = delta.before() == null;
            var day = insert ? new TradingCalendarDay() : UpdateEntity.of(TradingCalendarDay.class);
            day.setMarketItemId(delta.market().id());
            day.setCalendarDate(delta.date());
            day.setCalendarDate8(compact(delta.date()));
            day.setIsTradingDay(value.trading());
            day.setHolidayName(value.holiday());
            day.setSource(value.source());
            day.setRemark(value.remark());
            day.setUpdatedBy(actor);
            if (insert) {
                day.setCreatedBy(actor);
                inserts.add(day);
            } else {
                updates.add(day);
            }
        }
        if (!inserts.isEmpty()) {
            Db.executeBatch(inserts, BATCH_SIZE, TradingCalendarMapper.class, TradingCalendarMapper::insertSelective);
        }
        if (!updates.isEmpty()) {
            Db.executeBatch(updates, BATCH_SIZE, TradingCalendarMapper.class, TradingCalendarMapper::update);
        }
    }

    private static MarketItem market(ParamItem row) {
        return new MarketItem(row.getId(), new Market(row.getCode(), row.getName(), row.getNameEn(), row.getValue1(), row.getValue2()));
    }

    private static StoredDay day(TradingCalendarDay row) {
        var values = new Values(row.getIsTradingDay(), row.getHolidayName(), row.getSource(), row.getRemark());
        return new StoredDay(row.getCalendarDate(), values, row.getUpdatedAt());
    }

    static String compact(LocalDate date) {
        return DateTimeFormatter.BASIC_ISO_DATE.format(date);
    }
}
