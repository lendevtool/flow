package me.lendev.flow.calendar;

import static me.lendev.flow.calendar.TradingCalendarModels.*;
import static me.lendev.flow.calendar.TradingCalendarRepository.compact;
import static me.lendev.flow.common.response.BizResponseCode.TRADING_CALENDAR_BASE_CLOSED;
import static me.lendev.flow.common.response.BizResponseCode.TRADING_CALENDAR_COVERAGE_MISSING;

import cn.dev33.satoken.stp.StpUtil;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.calendar.TradingCalendarRepository.Delta;
import me.lendev.flow.calendar.TradingCalendarRepository.MarketItem;
import me.lendev.flow.calendar.TradingCalendarRepository.StoredDay;
import me.lendev.flow.calendar.TradingCalendarRepository.Values;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.exception.BizException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 按市场管理日历覆盖与交易状态，确认写入在同一事务内重建预览、检查令牌并审计。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TradingCalendarService {

    static final int MIN_YEAR = 1000;
    static final int MAX_YEAR = 9999;
    private static final LocalDate MIN_DATE = LocalDate.of(MIN_YEAR, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(MAX_YEAR, 12, 31);
    private static final int OFFSET_WINDOW = 128;
    public static final int MAX_SCHEDULE_OFFSET = 366;
    private static final int MAX_SCHEDULE_SEARCH_DAYS = 3660;
    private static final int MAX_MARKETS = 32;
    private static final int MAX_DATES = 3660;
    private static final String LIMIT_ERROR = "limit";
    private static final int MAX_PAIRS = 10_000;

    private final TradingCalendarRepository repository;
    private final MessageSource messages;

    public List<Market> markets() {
        return repository.markets().stream().map(MarketItem::market).toList();
    }

    /**
     * 返回指定年份已配置的记录，未覆盖日期不补齐。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Day> year(int year, List<String> marketCodes) {
        Year calendarYear = yearOf(year);
        return requireMarkets(marketCodes, false).stream().flatMap(market ->
                repository.range(market.id(), calendarYear.atDay(1), calendarYear.atMonth(12).atEndOfMonth(), true)
                        .stream().map(row -> day(market, row))).toList();
    }

    /**
     * 查询指定市场的日期状态；未覆盖的日期返回业务错误。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Day status(String code, LocalDate date) {
        requireDate(date);
        var market = requireMarket(code);
        return day(market, requireDay(market, date));
    }

    /**
     * 从已覆盖的基准日期向前或向后按交易日计数，扫描遇到覆盖缺口即中止。
     *
     * @param code 市场编码
     * @param date 基准日期
     * @param n    交易日偏移量，正数向未来、负数向过去，零要求基准日为交易日
     * @return 目标交易日
     * @throws BizException 日期覆盖缺失，或零偏移的基准日休市
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Offset offset(String code, LocalDate date, int n) {
        requireDate(date);
        MarketItem market = requireMarket(code);
        StoredDay base = requireDay(market, date);
        if (n == 0) {
            if (!base.values().trading()) {
                throw BizException.localized(TRADING_CALENDAR_BASE_CLOSED, "error.12002", market.market().code(), date);
            }
            return new Offset(date);
        }
        return new Offset(scanOffset(market, date, n, n > 0 ? MAX_DATE : MIN_DATE, false));
    }

    /**
     * 同一交易日历快照中计算待办日期，要求 T 已配置为交易日并限制搜索范围。
     *
     * @param code    市场编码
     * @param base    待办排期的交易日锚点 T
     * @param offsets 相对 T 的交易日偏移集合
     * @return 市场参数项 ID 和各偏移对应的交易日
     * @throws BadRequestException 偏移超出限制，或在搜索范围内无法确定目标
     * @throws BizException        交易日历存在覆盖缺口，或锚点休市
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ,
            noRollbackFor = {BadRequestException.class, BizException.class})
    public ScheduleOffsets scheduleOffsets(String code, LocalDate base, Set<Integer> offsets) {
        requireDate(base);
        MarketItem market = requireMarket(code);
        if (!requireDay(market, base).values().trading()) {
            throw BizException.localized(TRADING_CALENDAR_BASE_CLOSED, "error.12002", market.market().code(), base);
        }
        Map<Integer, LocalDate> dates = new TreeMap<>();
        var ordered = new TreeSet<>(offsets);
        if (!ordered.isEmpty()) {
            requireScheduleOffset(ordered.first());
        }
        var earlier = new OffsetScan(market, base, scheduleBound(base, false), false);
        for (int offset : ordered.headSet(0, false).descendingSet()) {
            dates.put(offset, requireScheduleResult(earlier.find(offset)));
        }
        if (ordered.contains(0)) {
            dates.put(0, base);
        }
        var later = new OffsetScan(market, base, scheduleBound(base, true), true);
        for (int offset : ordered.tailSet(0, false)) {
            requireScheduleOffset(offset);
            dates.put(offset, requireScheduleResult(later.find(offset)));
        }
        return new ScheduleOffsets(market.id(), Map.copyOf(dates));
    }

    /**
     * 从上一期锚点之后数交易日，已覆盖的锚点即使改为休市也可以继续计算。
     *
     * @param code     市场编码
     * @param previous 已处理的上一期锚点
     * @param interval 正数交易日间隔
     * @param until    最晚允许的锚点日期，为 {@code null} 时使用默认搜索上限
     * @return 下一期锚点，已到截止日期或完整覆盖至截止日期且不足一个间隔时返回 {@code null}
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ,
            noRollbackFor = {BadRequestException.class, BizException.class})
    public LocalDate nextRecurrenceBase(String code, LocalDate previous, int interval, LocalDate until) {
        requireDate(previous);
        requireScheduleOffset(interval);
        if (interval <= 0) {
            throw invalid("range");
        }
        if (until != null && !previous.isBefore(until)) {
            return null;
        }
        MarketItem market = requireMarket(code);
        requireDay(market, previous);
        return boundedOffset(market, previous, interval, until);
    }

    private static void requireScheduleOffset(int offset) {
        if (Math.abs((long) offset) > MAX_SCHEDULE_OFFSET) {
            throw invalid(LIMIT_ERROR);
        }
    }

    private LocalDate boundedOffset(MarketItem market, LocalDate date, int n, LocalDate until) {
        boolean forward = n > 0;
        LocalDate maximum = date.plusDays(forward ? MAX_SCHEDULE_SEARCH_DAYS : -MAX_SCHEDULE_SEARCH_DAYS);
        LocalDate domainEnd = forward ? MAX_DATE : MIN_DATE;
        LocalDate requestedEnd = until == null ? domainEnd : until;
        boolean bounded = forward ? maximum.isBefore(requestedEnd) : maximum.isAfter(requestedEnd);
        LocalDate result = scanOffset(market, date, n, bounded ? maximum : requestedEnd, true);
        return bounded || until == null ? requireScheduleResult(result) : result;
    }

    private static LocalDate scheduleBound(LocalDate base, boolean forward) {
        LocalDate maximum = base.plusDays(forward ? MAX_SCHEDULE_SEARCH_DAYS : -MAX_SCHEDULE_SEARCH_DAYS);
        LocalDate domainEnd = forward ? MAX_DATE : MIN_DATE;
        return beyond(maximum, domainEnd, forward) ? domainEnd : maximum;
    }

    private static LocalDate requireScheduleResult(LocalDate result) {
        if (result == null) {
            throw invalid(LIMIT_ERROR);
        }
        return result;
    }

    private LocalDate scanOffset(MarketItem market, LocalDate date, int n, LocalDate bound,
                                 boolean allowExhaustion) {
        var scan = new OffsetScan(market, date, bound, n > 0);
        LocalDate result = scan.find(n);
        if (result == null && !allowExhaustion) {
            throw coverage(market, scan.cursor);
        }
        return result;
    }

    /**
     * 单向顺序扫描，保留当前窗口和交易日计数，供后续更远的偏移继续使用。
     */
    private final class OffsetScan {

        private final MarketItem market;
        private final LocalDate bound;
        private final boolean forward;
        private final int step;
        private LocalDate cursor;
        private LocalDate windowEnd;
        private Iterator<StoredDay> rows = Collections.emptyIterator();
        private long traded;

        private OffsetScan(MarketItem market, LocalDate base, LocalDate bound, boolean forward) {
            this.market = market;
            this.bound = bound;
            this.forward = forward;
            step = forward ? 1 : -1;
            cursor = base.plusDays(step);
            windowEnd = base;
        }

        private LocalDate find(int offset) {
            long target = Math.abs((long) offset);
            while (!beyond(cursor, bound, forward)) {
                if (beyond(cursor, windowEnd, forward)) {
                    windowEnd = offsetWindowEnd(cursor, bound, forward);
                    rows = scanWindow().iterator();
                }
                if (!rows.hasNext()) {
                    throw coverage(market, cursor);
                }
                var row = rows.next();
                if (!cursor.equals(row.date())) {
                    throw coverage(market, cursor);
                }
                cursor = cursor.plusDays(step);
                if (row.values().trading() && ++traded == target) {
                    return row.date();
                }
            }
            return null;
        }

        private List<StoredDay> scanWindow() {
            return forward ? repository.range(market.id(), cursor, windowEnd, true)
                    : repository.range(market.id(), windowEnd, cursor, false);
        }
    }

    /**
     * 给出市场标识以及同一快照下计算的各个偏移日期。
     */
    public record ScheduleOffsets(long marketItemId, Map<Integer, LocalDate> dates) {

    }

    /**
     * 预览按周一至周五为交易日补齐指定年度覆盖，供确认时校验计划是否过期。
     *
     * @param request 初始化年份和市场范围
     * @return 新增、变更和未变更数量以及确认指纹
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewInitialize(Initialize request) {
        return initializePlan(request, false).preview();
    }

    /**
     * 只写入缺失的日期；已配置日期（含例外和补班）保持原样。
     *
     * @param request 初始化范围和预览返回的确认指纹
     * @return 加锁重建后的写入计划
     * @throws TradingCalendarConflictException 当前计划与预览不一致
     */
    @Audited(AuditOperation.TRADING_CALENDAR_INITIALIZE)
    @Transactional(rollbackFor = Exception.class)
    public TradingCalendarPlan applyInitialize(Initialize request) {
        return apply(Source.INIT, initializePlan(request, true), request.fingerprint());
    }

    /**
     * 预览手工设置的影响，包括日期范围内的交易状态与节假日变更。
     *
     * @param request 市场、日期和变更方式
     * @return 变更统计和确认指纹
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewBatch(Batch request) {
        return batchPlan(request, false).preview();
    }

    /**
     * 确认批量修改或单日设置；已存在日期的备注不受手工设置影响。
     *
     * @param request 批量设置内容和预览返回的确认指纹
     * @return 加锁重建后的写入计划
     * @throws TradingCalendarConflictException 当前计划与预览不一致
     */
    @Audited(AuditOperation.TRADING_CALENDAR_BATCH)
    @Transactional(rollbackFor = Exception.class)
    public TradingCalendarPlan applyBatch(Batch request) {
        return apply(Source.MANUAL, batchPlan(request, true), request.fingerprint());
    }

    /**
     * 校验已解析的表格并生成导入预览，存在行错误时不生成确认指纹。
     *
     * @param parsed 在事务外解析出的表格记录与行错误
     * @return 导入统计、行错误及可用的确认指纹
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview previewImport(TradingCalendarExcel.Parsed parsed) {
        return importPlan(parsed, false).preview();
    }

    /**
     * 导入已在事务外解析的表格；任何错误行都阻止整批写入。
     *
     * @param parsed      表格记录和解析错误
     * @param fingerprint 预览时返回的确认指纹
     * @return 加锁重建后的写入计划
     * @throws BadRequestException              存在错误行或缺少确认指纹
     * @throws TradingCalendarConflictException 当前计划与预览不一致
     */
    @Audited(AuditOperation.TRADING_CALENDAR_IMPORT)
    @Transactional(rollbackFor = Exception.class)
    public TradingCalendarPlan applyImport(TradingCalendarExcel.Parsed parsed, String fingerprint) {
        TradingCalendarPlan plan = importPlan(parsed, true);
        if (!plan.preview().errors().isEmpty()) {
            log.info("交易日历导入确认被拒绝，requestId={} rowCount={} issueCount={}",
                    AuditParticipants.currentRequestId(), parsed.rows().size(), plan.preview().errors().size());
            throw invalid("import-rejected");
        }
        return apply(Source.EXCEL, plan, fingerprint);
    }

    private TradingCalendarPlan initializePlan(Initialize request, boolean lock) {
        Year year = yearOf(request.year());
        List<LocalDate> dates = year.atDay(1).datesUntil(year.atDay(year.length()).plusDays(1)).toList();
        return plan(Source.INIT, forMarkets(requireMarkets(request.marketCodes(), lock), dates,
                date -> new Values(date.getDayOfWeek().compareTo(DayOfWeek.FRIDAY) <= 0,
                        null, Source.INIT, null)), lock, HolidayMode.KEEP);
    }

    private TradingCalendarPlan batchPlan(Batch request, boolean lock) {
        List<LocalDate> dates = batchDates(request);
        HolidayMode holidayMode = request.holidayMode() == null ? HolidayMode.KEEP : request.holidayMode();
        String holiday = request.holidayName() == null ? null : request.holidayName().strip();
        if (holidayMode == HolidayMode.SET && (holiday == null || holiday.isEmpty() || holiday.length() > 100)) {
            throw invalid("holiday");
        }
        if (request.isTradingDay() == null) {
            throw invalid("status");
        }
        return plan(Source.MANUAL, forMarkets(requireMarkets(request.marketCodes(), lock), dates,
                date -> new Values(request.isTradingDay(), holiday, Source.MANUAL, null)), lock, holidayMode);
    }

    private TradingCalendarPlan importPlan(TradingCalendarExcel.Parsed parsed, boolean lock) {
        List<Issue> issues = new ArrayList<>();
        parsed.errors().forEach(issue -> issues.add(new Issue(issue.row(), issue.message())));
        if (parsed.rows().isEmpty() && issues.isEmpty()) {
            issues.add(new Issue(0, localized("empty")));
        }
        if (parsed.rows().size() > MAX_PAIRS) {
            return TradingCalendarPlan.invalid(List.of(new Issue(0, localized(LIMIT_ERROR))));
        }
        if (parsed.rows().isEmpty()) {
            return TradingCalendarPlan.invalid(issues);
        }
        Map<String, MarketItem> markets = marketIndex(lock);
        Map<Long, MarketTargets> targets = new TreeMap<>();
        for (var row : parsed.rows()) {
            addImportRow(markets, targets, issues, row);
        }
        return plan(Source.EXCEL, List.copyOf(targets.values()), lock, HolidayMode.KEEP).withIssues(issues);
    }

    private void addImportRow(Map<String, MarketItem> markets, Map<Long, MarketTargets> targets,
                              List<Issue> issues, TradingCalendarExcel.Row row) {
        MarketItem market = markets.get(normalizedCode(row.marketCode()));
        if (market == null) {
            issues.add(new Issue(row.row(), localized("market", row.marketCode())));
            return;
        }
        var target = targets.computeIfAbsent(market.id(), ignored -> new MarketTargets(market, new TreeMap<>()));
        if (target.dates().putIfAbsent(row.date(),
                new Values(row.isTradingDay(), row.holidayName(), Source.EXCEL, row.remark())) != null) {
            issues.add(new Issue(row.row(), localized("duplicate")));
        }
    }

    private TradingCalendarPlan plan(Source source, List<MarketTargets> targets, boolean lock, HolidayMode holidayMode) {
        List<Delta> deltas = new ArrayList<>();
        for (MarketTargets target : targets) {
            Map<LocalDate, StoredDay> existing = new LinkedHashMap<>();
            repository.selected(target.market().id(), List.copyOf(target.dates().keySet()), lock)
                    .forEach(row -> existing.put(row.date(), row));
            target.dates().forEach((date, wanted) -> {
                StoredDay before = existing.get(date);
                deltas.add(new Delta(target.market(), date, before, resolve(before, wanted, holidayMode)));
            });
        }
        return TradingCalendarPlan.of(source, deltas);
    }

    private static Values resolve(StoredDay before, Values wanted, HolidayMode holidayMode) {
        Values old = before == null ? null : before.values();
        return switch (wanted.source()) {
            case INIT -> old == null ? wanted : old;
            case EXCEL -> preserveSourceIfUnchanged(old, wanted);
            case MANUAL -> {
                String holiday = switch (holidayMode) {
                    case KEEP -> old == null ? null : old.holiday();
                    case SET -> wanted.holiday();
                    case CLEAR -> null;
                };
                yield preserveSourceIfUnchanged(old, new Values(wanted.trading(), holiday, Source.MANUAL,
                        old == null ? null : old.remark()));
            }
        };
    }

    private static Values preserveSourceIfUnchanged(Values before, Values wanted) {
        return before != null && before.trading() == wanted.trading()
                && Objects.equals(before.holiday(), wanted.holiday()) && Objects.equals(before.remark(), wanted.remark())
                ? before : wanted;
    }

    private TradingCalendarPlan apply(Source source, TradingCalendarPlan plan, String fingerprint) {
        var preview = plan.preview();
        if (fingerprint == null || fingerprint.isBlank()) {
            throw invalid("preview-required");
        }
        if (!fingerprint.equals(preview.fingerprint())) {
            log.info("交易日历预览已过期，requestId={} source={} targetCount={}",
                    AuditParticipants.currentRequestId(), source, plan.deltas().size());
            throw new TradingCalendarConflictException();
        }
        if (preview.added() + preview.changed() > 0) {
            repository.apply(plan.deltas(), StpUtil.getLoginIdAsLong());
        }
        log.info("交易日历确认处理完成，requestId={} source={} added={} changed={} unchanged={}",
                AuditParticipants.currentRequestId(), source, preview.added(), preview.changed(), preview.unchanged());
        return plan;
    }

    private static List<MarketTargets> forMarkets(List<MarketItem> markets, List<LocalDate> dates,
                                                  java.util.function.Function<LocalDate, Values> values) {
        if ((long) markets.size() * dates.size() > MAX_PAIRS) {
            throw invalid(LIMIT_ERROR);
        }
        Map<LocalDate, Values> byDate = new LinkedHashMap<>();
        dates.forEach(date -> byDate.put(date, values.apply(date)));
        return markets.stream().map(market -> new MarketTargets(market, byDate)).toList();
    }

    private static List<LocalDate> batchDates(Batch request) {
        var dates = new TreeSet<LocalDate>();
        if (request.dates() != null) {
            request.dates().forEach(date -> {
                requireDate(date);
                dates.add(date);
            });
        }
        if (request.startDate() != null || request.endDate() != null) {
            requireDate(request.startDate());
            requireDate(request.endDate());
            long distance = ChronoUnit.DAYS.between(request.startDate(), request.endDate());
            if (distance < 0) {
                throw invalid("range");
            }
            if (distance >= MAX_DATES) {
                throw invalid(LIMIT_ERROR);
            }
            request.startDate().datesUntil(request.endDate().plusDays(1)).forEach(dates::add);
        }
        if (dates.isEmpty()) {
            throw invalid("empty");
        }
        if (dates.size() > MAX_DATES) {
            throw invalid(LIMIT_ERROR);
        }
        return List.copyOf(dates);
    }

    private Map<String, MarketItem> marketIndex(boolean lock) {
        Map<String, MarketItem> result = new LinkedHashMap<>();
        var items = lock ? repository.lockMarkets() : repository.markets();
        items.forEach(item -> result.put(normalizedCode(item.market().code()), item));
        return result;
    }

    List<MarketItem> requireMarkets(List<String> codes, boolean lock) {
        if (codes == null || codes.isEmpty() || codes.size() > MAX_MARKETS) {
            throw invalid("market-selection");
        }
        var markets = marketIndex(lock);
        var selected = new TreeMap<Long, MarketItem>();
        for (String code : codes) {
            MarketItem market = markets.get(normalizedCode(code));
            if (market == null) {
                throw invalid("market", code);
            }
            selected.put(market.id(), market);
        }
        return List.copyOf(selected.values());
    }

    private MarketItem requireMarket(String code) {
        return requireMarkets(java.util.Collections.singletonList(code), false).getFirst();
    }

    private StoredDay requireDay(MarketItem market, LocalDate date) {
        return repository.range(market.id(), date, date, true).stream().findFirst()
                .orElseThrow(() -> coverage(market, date));
    }

    private static Day day(MarketItem market, StoredDay row) {
        return new Day(market.market().code(), row.date(), compact(row.date()), row.values().trading(),
                row.values().holiday(), row.values().source(), row.values().remark());
    }

    private static boolean beyond(LocalDate date, LocalDate bound, boolean forward) {
        return forward ? date.isAfter(bound) : date.isBefore(bound);
    }

    private static LocalDate offsetWindowEnd(LocalDate date, LocalDate bound, boolean forward) {
        LocalDate end = date.plusDays(forward ? OFFSET_WINDOW - 1L : 1L - OFFSET_WINDOW);
        LocalDate domainEnd = forward ? MAX_DATE : MIN_DATE;
        if (beyond(end, domainEnd, forward)) {
            end = domainEnd;
        }
        return beyond(end, bound, forward) ? bound : end;
    }

    private static Year yearOf(int year) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw invalid("year");
        }
        return Year.of(year);
    }

    private static void requireDate(LocalDate date) {
        if (date == null || date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE)) {
            throw invalid("date");
        }
    }

    private static String normalizedCode(String code) {
        return code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
    }

    private static BizException coverage(MarketItem market, LocalDate missingDate) {
        return BizException.localized(TRADING_CALENDAR_COVERAGE_MISSING, "error.12001", market.market().code(), missingDate);
    }

    private static BadRequestException invalid(String key, Object... arguments) {
        return BadRequestException.localized("error.trading-calendar." + key, arguments);
    }

    private String localized(String key, Object... arguments) {
        return messages.getMessage("error.trading-calendar." + key, arguments, LocaleContextHolder.getLocale());
    }

    private record MarketTargets(MarketItem market, Map<LocalDate, Values> dates) {

    }
}
