package me.lendev.flow.workbench.series;

import static me.lendev.flow.workbench.series.SeriesModels.*;
import static me.lendev.flow.workbench.series.entity.table.TodoSeriesTableDef.TODO_SERIES;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.workbench.series.entity.TodoSeries;
import me.lendev.flow.workbench.series.mapper.SeriesMapper;
import me.lendev.flow.workbench.series.mapper.SeriesMapper.RuleRow;
import me.lendev.flow.workbench.todo.mapper.TodoMapper;
import org.springframework.stereotype.Repository;

/**
 * 持久化周期规则与游标，按规则和实例 ID 顺序锁定未来变更的影响范围。
 */
@Repository
@RequiredArgsConstructor
@Slf4j
class SeriesRepository {

    private final SeriesMapper mapper;

    /**
     * 规则及其关联的市场参数项 ID，非交易规则市场 ID 为 null。
     */
    record Stored(Rule rule, Long marketItemId) {

    }

    /**
     * 交易日历覆盖的起止日期，无日历数据时两者均为 null。
     */
    record Coverage(LocalDate start, LocalDate end) {

    }

    /**
     * 按规则 ID 倒序查询规则，owner 为 null 时返回全部。
     */
    List<Rule> list(Integer owner) {
        return mapper.list(owner).stream().map(row -> stored(row).rule()).toList();
    }

    /**
     * 普通读取规则，不存在时返回 null。
     */
    Stored find(long id) {
        var row = mapper.find(id, false);
        return row == null ? null : stored(row);
    }

    /**
     * 以 FOR UPDATE 锁定规则行后读取，不存在时返回 null，须在事务内调用。
     */
    Stored lock(long id) {
        var row = mapper.find(id, true);
        return row == null ? null : stored(row);
    }

    /**
     * 选取检查时间已到期的活跃规则 ID，按检查时间升序。
     */
    List<Long> due(Instant now, int limit) {
        return mapper.due(utc(now), limit);
    }

    /**
     * 查询启用状态的市场参数项 ID，市场不存在或停用时返回 null。
     */
    Long activeMarket(String code) {
        return mapper.activeMarketIds(Objects.requireNonNull(code)).stream().findFirst().orElse(null);
    }

    /**
     * 查询市场交易日历的覆盖范围，market 为 null 时返回空覆盖。
     */
    Coverage coverage(Long market) {
        var row = market == null ? null : mapper.coverage(market);
        return row == null ? new Coverage(null, null) : new Coverage(row.getFromDate(), row.getToDate());
    }

    /**
     * 插入新规则并返回自增 ID。
     */
    long insert(Stored stored, Instant now) {
        var row = new TodoSeries();
        row.setOwnerUserId(stored.rule().ownerUserId());
        row.setOwnerName(stored.rule().ownerName());
        row.setCreatedAt(utc(now));
        applyRule(row, stored);
        applyProgress(row, stored.rule(), now);
        mapper.insertSelective(row);
        return row.getId();
    }

    /**
     * 整体覆盖规则内容与生成游标，调用方须已锁定规则行。
     */
    void updateRule(Stored stored, Instant now) {
        var row = UpdateWrapper.of(TodoSeries.class).toEntity();
        applyRule(row, stored);
        applyProgress(row, stored.rule(), now);
        mapper.updateByCondition(row, TODO_SERIES.ID.eq(stored.rule().id()));
    }

    /**
     * 仅更新生成游标与状态，不改动规则内容，调用方须已锁定规则行。
     */
    void updateProgress(Rule rule, Instant now) {
        var row = UpdateWrapper.of(TodoSeries.class).toEntity();
        applyProgress(row, rule, now);
        mapper.updateByCondition(row, TODO_SERIES.ID.eq(rule.id()));
    }

    /**
     * 条件更新检查时间：仅当规则仍活跃且检查时间未晚于 now 时延期，避免覆盖并发推进。
     */
    void defer(long id, Instant now, Instant retry) {
        mapper.defer(id, utc(now), utc(retry));
    }

    /**
     * 查询生效日之后受变更影响的开放实例，lock 为 true 时按 ID 顺序加行锁。
     */
    List<Affected> affected(long id, LocalDate cutoff, ChangeAction action, boolean lock) {
        return mapper.affected(id, cutoff, action == ChangeAction.STOP, lock);
    }

    /**
     * 批量取消受影响实例并记录取消类型，空列表直接返回。
     */
    void cancel(List<Affected> affected, ChangeAction action, int actor, Instant now) {
        if (affected.isEmpty()) {
            return;
        }
        boolean stop = action == ChangeAction.STOP;
        String kind = stop ? "SERIES_STOP" : "SERIES_CHANGE";
        String reason = stop ? "周期规则已停止" : "周期规则已变更";
        LocalDateTime cancelledAt = utc(now);
        Db.executeBatch(affected, TodoMapper.class, (batch, row) ->
                batch.cancelSeriesOccurrence(row.id(), kind, reason, actor, cancelledAt));
        log.info("周期规则变更已取消未来实例，动作={}，取消数量={}", action, affected.size());
    }

    /**
     * 判断锚点是否已有保留实例（未被规则变更取消），用于生成时跳过重复期次。
     */
    boolean hasRetainedOccurrence(long id, LocalDate anchor) {
        return mapper.retainedOccurrence(id, anchor) != null;
    }

    private static void applyRule(TodoSeries row, Stored stored) {
        var rule = stored.rule();
        var fields = rule.rule();
        row.setAssigneeUserId(fields.assigneeUserId());
        row.setTitle(fields.title());
        row.setDescription(fields.description());
        row.setPriority(fields.priority());
        row.setFrequency(fields.frequency());
        row.setIntervalValue(fields.intervalValue());
        row.setWeekdaysMask(fields.weekdaysMask());
        row.setMonthDayMode(fields.monthDayMode());
        row.setAnchorDate(fields.anchorDate());
        row.setUntilDate(fields.untilDate());
        row.setDurationDays(fields.durationDays());
        row.setDueOffsetDays(fields.dueOffsetDays());
        row.setMarketItemId(stored.marketItemId());
        row.setStartTradeOffset(fields.startTradeOffset());
        row.setEndTradeOffset(fields.endTradeOffset());
        row.setDueTradeOffset(fields.dueTradeOffset());
        var dimensions = fields.dimensions();
        row.setDimension1ItemId(dimensions.get(1));
        row.setDimension2ItemId(dimensions.get(2));
        row.setDimension3ItemId(dimensions.get(3));
        row.setDimension4ItemId(dimensions.get(4));
        row.setDimension5ItemId(dimensions.get(5));
        row.setRuleRevision(rule.ruleRevision());
        row.setEffectiveFromDate(rule.effectiveFromDate());
    }

    private static void applyProgress(TodoSeries row, Rule rule, Instant now) {
        row.setStatus(rule.status());
        row.setLastBaseDate(rule.lastBaseDate());
        row.setNextBaseDate(rule.nextBaseDate());
        row.setNextCheckAt(utc(rule.nextCheckAt()));
        row.setBlockedDate(rule.blockedDate());
        row.setBlockReason(rule.blockReason());
        row.setVersion(rule.version());
        row.setUpdatedAt(utc(now));
    }

    private static Stored stored(RuleRow row) {
        var dimensions = new LinkedHashMap<Integer, Long>();
        Long[] items = {row.getDimension1ItemId(), row.getDimension2ItemId(), row.getDimension3ItemId(),
                row.getDimension4ItemId(), row.getDimension5ItemId()};
        for (int slot = 1; slot <= items.length; slot++) {
            Long item = items[slot - 1];
            if (item != null) {
                dimensions.put(slot, item);
            }
        }
        String market = row.getMarketCode();
        var write = new Write(row.getTitle(), row.getDescription(), row.getAssigneeUserId(), row.getPriority(), row.getFrequency(),
                row.getIntervalValue(), row.getWeekdaysMask(), row.getMonthDayMode(), row.getAnchorDate(), row.getUntilDate(),
                row.getDurationDays(), row.getDueOffsetDays(), market == null ? null : market.toUpperCase(Locale.ROOT),
                row.getStartTradeOffset(), row.getEndTradeOffset(), row.getDueTradeOffset(), dimensions);
        LocalDateTime check = row.getNextCheckAt();
        var rule = new Rule(row.getId(), row.getOwnerUserId(), row.getOwnerName(), write, row.getStatus(), row.getRuleRevision(),
                row.getLastBaseDate(), row.getNextBaseDate(), row.getEffectiveFromDate(),
                check == null ? null : check.toInstant(ZoneOffset.UTC), row.getBlockedDate(), row.getBlockReason(), row.getVersion());
        return new Stored(rule, row.getMarketItemId());
    }

    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
