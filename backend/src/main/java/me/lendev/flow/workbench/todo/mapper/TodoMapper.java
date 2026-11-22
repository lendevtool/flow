package me.lendev.flow.workbench.todo.mapper;

import static com.mybatisflex.core.query.QueryMethods.case_;
import static com.mybatisflex.core.query.QueryMethods.coalesce;
import static com.mybatisflex.core.query.QueryMethods.column;
import static com.mybatisflex.core.query.QueryMethods.count;
import static com.mybatisflex.core.query.QueryMethods.exists;
import static com.mybatisflex.core.query.QueryMethods.sum;
import static me.lendev.flow.param.domain.table.ParamGroupTableDef.PARAM_GROUP;
import static me.lendev.flow.param.domain.table.ParamItemTableDef.PARAM_ITEM;
import static me.lendev.flow.pms.domain.entity.table.UserTableDef.USER;
import static me.lendev.flow.workbench.series.entity.table.TodoSeriesTableDef.TODO_SERIES;
import static me.lendev.flow.workbench.todo.TodoModels.*;
import static me.lendev.flow.workbench.todo.entity.table.TodoInstanceTableDef.TODO_INSTANCE;
import static me.lendev.flow.workbench.todo.entity.table.TodoPersonalValueTableDef.TODO_PERSONAL_VALUE;

import com.mybatisflex.annotation.TableRef;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateWrapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import me.lendev.flow.workbench.todo.entity.TodoInstance;

/**
 * 待办实例的读写、行锁和按同一条件查询列表及统计。
 */
public interface TodoMapper extends BaseMapper<TodoInstance> {

    /**
     * 普通读取待办并联表补充市场编码，不存在时返回 null。
     */
    default TodoRow find(long id) {
        return selectOneByQueryAs(joinedTodos().where(TODO_INSTANCE.ID.eq(id)), TodoRow.class);
    }

    /**
     * 只锁定待办实例，市场编码从标量子查询读取。
     */
    default TodoRow lock(long id) {
        var marketCode = QueryWrapper.create().select(PARAM_ITEM.CODE).from(PARAM_ITEM)
                .where(PARAM_ITEM.ID.eq(TODO_INSTANCE.MARKET_ITEM_ID));
        return selectOneByQueryAs(QueryWrapper.create()
                .select(TODO_INSTANCE.ALL_COLUMNS, column(marketCode).as("marketCode")).from(TODO_INSTANCE)
                .where(TODO_INSTANCE.ID.eq(id)).forUpdate(), TodoRow.class);
    }

    /**
     * 以 FOR UPDATE 锁定周期规则行，与规则变更保持加锁顺序。
     */
    default Long lockSeries(long id) {
        return selectObjectByQueryAs(QueryWrapper.create().select(TODO_SERIES.ID).from(TODO_SERIES)
                .where(TODO_SERIES.ID.eq(id)).forUpdate(), Long.class);
    }

    /**
     * 按幂等作用域与请求键查询待办，用于创建幂等回放。
     */
    default TodoRow byRequest(String scope, String key) {
        return selectOneByQueryAs(joinedTodos().where(TODO_INSTANCE.REQUEST_SCOPE.eq(scope, true))
                .and(TODO_INSTANCE.REQUEST_KEY.eq(key, true)), TodoRow.class);
    }

    /**
     * 按规则、修订号与锚点查询周期实例，用于生成幂等。
     */
    default TodoRow occurrence(long seriesId, int revision, LocalDate baseDate) {
        return selectOneByQueryAs(joinedTodos().where(TODO_INSTANCE.SERIES_ID.eq(seriesId))
                        .and(TODO_INSTANCE.SERIES_REVISION.eq(revision)).and(TODO_INSTANCE.SERIES_BASE_DATE.eq(baseDate, true)),
                TodoRow.class);
    }

    /**
     * 按版本号条件更新内容并递增版本，edited 为 true 时标记周期实例已单独编辑。
     * 返回影响行数，0 表示版本已变化。
     */
    default int edit(long id, long version, Fields fields, boolean edited, LocalDateTime now) {
        var update = UpdateWrapper.of(TodoInstance.class);
        TodoInstance row = update.toEntity();
        applyFields(row, fields);
        row.setOccurrenceEdited(edited);
        row.setUpdatedAt(now);
        update.set(TODO_INSTANCE.VERSION, TODO_INSTANCE.VERSION.add(1));
        return updateByQuery(row, versionCondition(id, version));
    }

    /**
     * 按版本号条件流转状态并递增版本，返回影响行数，0 表示版本已变化。
     */
    default int transition(StatusChange change) {
        var update = UpdateWrapper.of(TodoInstance.class)
                .set(TODO_INSTANCE.STATUS, change.status())
                .set(TODO_INSTANCE.CLOSED_AT, change.closedAt())
                .set(TODO_INSTANCE.CLOSED_BY, change.closedBy())
                .set(TODO_INSTANCE.CANCEL_KIND, change.cancelKind())
                .set(TODO_INSTANCE.CANCEL_REASON, change.reason())
                .set(TODO_INSTANCE.UPDATED_AT, change.now())
                .set(TODO_INSTANCE.VERSION, TODO_INSTANCE.VERSION.add(1));
        return updateByQuery(update.toEntity(), versionCondition(change.id(), change.version()));
    }

    /**
     * 按版本号条件改派负责人，周期实例同时标记已单独编辑。返回影响行数。
     */
    default int assign(long id, long version, Person person, LocalDateTime now) {
        var edited = case_().when(TODO_INSTANCE.SERIES_ID.isNotNull())
                .then(true).else_(TODO_INSTANCE.OCCURRENCE_EDITED).end();
        var update = UpdateWrapper.of(TodoInstance.class)
                .set(TODO_INSTANCE.ASSIGNEE_USER_ID, person.id())
                .set(TODO_INSTANCE.ASSIGNEE_NAME, person.name())
                .set(TODO_INSTANCE.UPDATED_AT, now)
                .set(TODO_INSTANCE.VERSION, TODO_INSTANCE.VERSION.add(1))
                .set(TODO_INSTANCE.OCCURRENCE_EDITED, edited);
        return updateByQuery(update.toEntity(), versionCondition(id, version));
    }

    /**
     * 取消已由周期变更事务锁定的实例。
     */
    default void cancelSeriesOccurrence(long id, String kind, String reason, int actor, LocalDateTime now) {
        var update = UpdateWrapper.of(TodoInstance.class)
                .set(TODO_INSTANCE.STATUS, Status.CANCELLED)
                .set(TODO_INSTANCE.CANCEL_KIND, kind)
                .set(TODO_INSTANCE.CANCEL_REASON, reason)
                .set(TODO_INSTANCE.CLOSED_BY, actor)
                .set(TODO_INSTANCE.CLOSED_AT, now)
                .set(TODO_INSTANCE.UPDATED_AT, now)
                .set(TODO_INSTANCE.VERSION, TODO_INSTANCE.VERSION.add(1));
        updateByCondition(update.toEntity(), TODO_INSTANCE.ID.eq(id));
    }

    /**
     * 查询用户快照。allPeople 为 true 时包含停用账号，lock 为 true 时加行锁。
     */
    default Person person(int id, boolean allPeople, boolean lock) {
        var query = peopleQuery().where(USER.ID.eq(id)).and(USER.ENABLE.eq(true, !allPeople));
        if (lock) {
            query.forUpdate();
        }
        var row = selectOneByQueryAs(query, PersonRow.class);
        return row == null ? null : new Person(row.getId(), row.getName());
    }

    /**
     * 查询用户当前的主部门，未设置时返回空。
     */
    default Long primaryDepartmentId(int userId) {
        return selectObjectByQueryAs(QueryWrapper.create().select(USER.DEPARTMENT_ID).from(USER)
                .where(USER.ID.eq(userId)), Long.class);
    }

    /**
     * 查询启用用户列表，按展示名与 ID 升序。
     */
    default List<Person> people() {
        var query = peopleQuery().where(USER.ENABLE.eq(true)).orderBy(personName().asc(), USER.ID.asc());
        return selectListByQueryAs(query, PersonRow.class).stream()
                .map(row -> new Person(row.getId(), row.getName())).toList();
    }

    /**
     * 按编码查询交易市场参数项 ID。
     */
    default List<Long> marketIds(String code) {
        return selectObjectListByQueryAs(QueryWrapper.create().select(PARAM_ITEM.ID).from(PARAM_ITEM)
                .innerJoin(PARAM_GROUP).on(PARAM_GROUP.ID.eq(PARAM_ITEM.GROUP_ID))
                .where(PARAM_GROUP.CODE.eq("TRADING_MARKET")).and(PARAM_ITEM.CODE.eq(code, true)), Long.class);
    }

    /**
     * 按同一筛选条件统计各范围数量，不受当前 scope 限制。
     */
    default ScopeCounts counts(Filters filters) {
        return selectOneByQueryAs(listDates(filtered(QueryWrapper.create().select(
                        count().as("allCount"), countScope(mine(filters)).as("mineCount"),
                        countScope(owned(filters)).as("createdCount"), countScope(done(filters)).as("doneCount"))
                .from(TODO_INSTANCE), filters), filters), ScopeCounts.class);
    }

    /**
     * 分页查询待办，按逾期、今日、未来、无排期、已关闭分级排序。
     */
    default List<TodoRow> page(Filters filters, int size, long offset) {
        var query = listDates(filtered(joinedTodos(), filters), filters).and(scope(filters)).limit(offset, size);
        return selectListByQueryAs(sorted(query, filters.today()), TodoRow.class);
    }

    /**
     * 日历区间查询：计划区间与查询区间相交，或无计划时截止日落在区间内。
     */
    default List<CalendarTodo> calendar(Filters filters, boolean showCompleted) {
        var person = "created".equals(filters.scope()) ? TODO_INSTANCE.OWNER_USER_ID : TODO_INSTANCE.ASSIGNEE_USER_ID;
        var query = filtered(QueryWrapper.create().select(TODO_INSTANCE.ID, TODO_INSTANCE.TITLE, TODO_INSTANCE.STATUS,
                TODO_INSTANCE.START_DATE, TODO_INSTANCE.END_DATE, TODO_INSTANCE.DUE_DATE).from(TODO_INSTANCE), filters)
                .and(person.eq(filters.viewer()))
                .and(showCompleted ? TODO_INSTANCE.STATUS.ne(Status.CANCELLED) : open())
                .and((QueryWrapper dates) -> {
                    dates.where(TODO_INSTANCE.START_DATE.le(filters.toDate()))
                            .and(TODO_INSTANCE.END_DATE.ge(filters.fromDate()))
                            .or(TODO_INSTANCE.START_DATE.isNull()
                                    .and(TODO_INSTANCE.DUE_DATE.between(filters.fromDate(), filters.toDate())));
                });
        return selectListByQuery(sorted(query, filters.today())).stream()
                .map(row -> new CalendarTodo(row.getId(), row.getTitle(), row.getStatus(), row.getStartDate(),
                        row.getEndDate(), row.getDueDate())).toList();
    }

    /**
     * 按公共维度槽位分组统计，未填写归入 null 组。
     */
    default List<StatisticRow> publicStatistics(Filters filters, int slot) {
        var key = publicDimension(slot);
        return statistics(QueryWrapper.create().from(TODO_INSTANCE), filters, key == null ? column("NULL") : key);
    }

    /**
     * 按当前用户的个人标记槽位分组统计，未标记归入 null 组。
     */
    default List<StatisticRow> personalStatistics(Filters filters, int slot) {
        var tag = TODO_PERSONAL_VALUE.as("tag");
        var query = QueryWrapper.create().from(TODO_INSTANCE).leftJoin(tag)
                .on(tag.TODO_ID.eq(TODO_INSTANCE.ID).and(tag.USER_ID.eq(filters.viewer())).and(tag.SLOT_NO.eq(slot)));
        return statistics(query, filters, tag.OPTION_KEY);
    }

    private List<StatisticRow> statistics(QueryWrapper query, Filters filters, QueryColumn key) {
        query.select(key.as("groupKey"), count().as("total"),
                sum(indicator(open())).as("opened"),
                sum(indicator(TODO_INSTANCE.STATUS.eq(Status.DONE))).as("done"),
                sum(indicator(open().and(TODO_INSTANCE.DUE_DATE.lt(filters.today())))).as("overdue"));
        return selectListByQueryAs(listDates(filtered(query, filters), filters)
                .and(scope(filters)).groupBy("groupKey"), StatisticRow.class);
    }

    private static QueryWrapper joinedTodos() {
        return QueryWrapper.create().select(TODO_INSTANCE.ALL_COLUMNS, PARAM_ITEM.CODE.as("marketCode"))
                .from(TODO_INSTANCE).leftJoin(PARAM_ITEM).on(PARAM_ITEM.ID.eq(TODO_INSTANCE.MARKET_ITEM_ID));
    }

    private static QueryWrapper peopleQuery() {
        return QueryWrapper.create().select(USER.ID, personName().as("name")).from(USER);
    }

    private static QueryColumn personName() {
        return column("COALESCE(NULLIF(nameCn,''),NULLIF(nameEn,''),username)");
    }

    private static QueryWrapper versionCondition(long id, long version) {
        return QueryWrapper.create().where(TODO_INSTANCE.ID.eq(id)).and(TODO_INSTANCE.VERSION.eq(version));
    }

    private static QueryWrapper filtered(QueryWrapper query, Filters filters) {
        query.where((QueryWrapper q) -> {
                    q.where(TODO_INSTANCE.OWNER_USER_ID.eq(filters.viewer()))
                            .or(TODO_INSTANCE.ASSIGNEE_USER_ID.eq(filters.viewer()));
                })
                .and(TODO_INSTANCE.ORIGIN.eq(filters.origin(), !filters.origin().isEmpty()))
                .and(status(filters))
                .and(TODO_INSTANCE.STATUS.ne(Status.CANCELLED, filters.completedJump()));
        if (filters.keyword() != null) {
            query.and("LOWER(wb_todo.title) LIKE LOWER(?) ESCAPE '!'", filters.keyword());
        }
        filters.publicFilters().forEach((slot, values) -> {
            var dimension = publicDimension(slot);
            if (dimension != null && !values.isEmpty()) {
                query.and(dimension.in(values));
            }
        });
        for (var filter : filters.personalFilters()) {
            query.and(personalMatch(filters.viewer(), filter));
        }
        return query;
    }

    private static QueryCondition personalMatch(int viewer, PersonalFilter filter) {
        var tag = TODO_PERSONAL_VALUE.as("p");
        var query = QueryWrapper.create().select(tag.TODO_ID).from(tag).where(tag.TODO_ID.eq(TODO_INSTANCE.ID))
                .and(tag.USER_ID.eq(viewer)).and(tag.SLOT_NO.eq(filter.slot()));
        if (filter.text()) {
            query.and((QueryWrapper q) -> filter.values().forEach(value -> q.or("LOCATE(?, p.optionKey) > 0", value)));
        } else {
            query.and(tag.OPTION_KEY.in(filter.values()));
        }
        return exists(query);
    }

    private static QueryColumn publicDimension(int slot) {
        return switch (slot) {
            case 1 -> TODO_INSTANCE.DIMENSION1_ITEM_ID;
            case 2 -> TODO_INSTANCE.DIMENSION2_ITEM_ID;
            case 3 -> TODO_INSTANCE.DIMENSION3_ITEM_ID;
            case 4 -> TODO_INSTANCE.DIMENSION4_ITEM_ID;
            case 5 -> TODO_INSTANCE.DIMENSION5_ITEM_ID;
            default -> null;
        };
    }

    private static QueryWrapper listDates(QueryWrapper query, Filters filters) {
        var from = filters.plan() ? TODO_INSTANCE.END_DATE : TODO_INSTANCE.DUE_DATE;
        var to = filters.plan() ? TODO_INSTANCE.START_DATE : TODO_INSTANCE.DUE_DATE;
        return query.and(from.ge(filters.fromDate(), filters.fromDate() != null))
                .and(to.le(filters.toDate(), filters.toDate() != null));
    }

    private static QueryCondition status(Filters filters) {
        return switch (filters.status()) {
            case "" -> QueryCondition.createEmpty();
            case "UNSCHEDULED" -> TODO_INSTANCE.START_DATE.isNull().and(TODO_INSTANCE.DUE_DATE.isNull());
            case "OVERDUE" -> open().and(TODO_INSTANCE.DUE_DATE.lt(filters.today()));
            case "TODAY" -> open().and(TODO_INSTANCE.DUE_DATE.eq(filters.today()));
            default -> TODO_INSTANCE.STATUS.eq(filters.status());
        };
    }

    private static QueryCondition scope(Filters filters) {
        return switch (filters.scope()) {
            case "mine" -> mine(filters);
            case "created" -> owned(filters);
            case "done" -> done(filters);
            case "all" -> QueryCondition.createEmpty();
            default -> com.mybatisflex.core.query.QueryMethods.raw("1 = 0");
        };
    }

    private static QueryCondition open() {
        return TODO_INSTANCE.STATUS.in(Status.OPEN, Status.IN_PROGRESS);
    }

    private static QueryCondition mine(Filters filters) {
        var assignee = TODO_INSTANCE.ASSIGNEE_USER_ID.eq(filters.viewer());
        return filters.completedJump() ? assignee : assignee.and(open());
    }

    private static QueryCondition owned(Filters filters) {
        var owner = TODO_INSTANCE.OWNER_USER_ID.eq(filters.viewer());
        return filters.includeOwnedClosed() ? owner : owner.and(open());
    }

    private static QueryCondition done(Filters filters) {
        return TODO_INSTANCE.ASSIGNEE_USER_ID.eq(filters.viewer()).and(TODO_INSTANCE.STATUS.eq(Status.DONE));
    }

    private static QueryColumn indicator(QueryCondition condition) {
        return case_().when(condition).then(1).else_(0).end();
    }

    private static QueryColumn countScope(QueryCondition condition) {
        return coalesce(sum(indicator(condition)), column("0"));
    }

    private static QueryWrapper sorted(QueryWrapper query, LocalDate today) {
        var dueRank = case_().when(TODO_INSTANCE.STATUS.in(Status.DONE, Status.CANCELLED)).then(4)
                .when(TODO_INSTANCE.DUE_DATE.lt(today)).then(0)
                .when(TODO_INSTANCE.DUE_DATE.eq(today)).then(1)
                .when(TODO_INSTANCE.DUE_DATE.gt(today)).then(2).else_(3).end();
        var priorityRank = case_(TODO_INSTANCE.PRIORITY)
                .when(Priority.HIGH.name()).then(0).when(Priority.NORMAL.name()).then(1).else_(2).end();
        return query.orderBy(dueRank.asc(), priorityRank.asc(), TODO_INSTANCE.DUE_DATE.asc(), TODO_INSTANCE.ID.asc());
    }

    /**
     * 将创建和编辑共用的字段写入持久化对象。
     */
    static void applyFields(TodoInstance row, Fields fields) {
        row.setTitle(fields.title());
        row.setDescription(fields.description());
        row.setPriority(Priority.valueOf(fields.priority()));
        row.setAssigneeUserId(fields.assignee().id());
        row.setAssigneeName(fields.assignee().name());
        row.setScheduleMode(fields.mode());
        row.setStartDate(fields.dates().startDate());
        row.setEndDate(fields.dates().endDate());
        row.setDueDate(fields.dates().dueDate());
        row.setMarketItemId(fields.marketId());
        row.setTradeBaseDate(fields.tradeBase());
        row.setStartTradeOffset(fields.startOffset());
        row.setEndTradeOffset(fields.endOffset());
        row.setDueTradeOffset(fields.dueOffset());
        row.setDimension1ItemId(fields.dimension1());
        row.setDimension2ItemId(fields.dimension2());
        row.setDimension3ItemId(fields.dimension3());
        row.setDimension4ItemId(fields.dimension4());
        row.setDimension5ItemId(fields.dimension5());
    }

    /**
     * 待办行附带联表查询出的市场编码。
     */
    @Getter
    @Setter
    @TableRef(TodoInstance.class)
    class TodoRow extends TodoInstance {

        private String marketCode;
    }

    /**
     * 用户 ID 与展示名查询行。
     */
    @Getter
    @Setter
    class PersonRow {

        private int id;
        private String name;
    }

    /**
     * 各范围数量统计行。
     */
    @Getter
    @Setter
    class ScopeCounts {

        private long allCount;
        private long mineCount;
        private long createdCount;
        private long doneCount;
    }

    /**
     * 维度分组统计行，groupKey 为 null 表示未填写。
     */
    @Getter
    @Setter
    class StatisticRow {

        private String groupKey;
        private long total;
        private long opened;
        private long done;
        private long overdue;
    }

    /**
     * 列表、日历与统计共用的规范化筛选条件，keyword 为已转义的 LIKE 模式。
     */
    record Filters(int viewer, LocalDate today, String scope, String keyword, String origin, String status,
                   boolean plan, boolean completedJump, boolean includeOwnedClosed, LocalDate fromDate,
                   LocalDate toDate, Map<Integer, List<Long>> publicFilters, List<PersonalFilter> personalFilters) {

    }

    /**
     * 文本字段使用字面子串，选项字段使用完整键匹配。
     */
    record PersonalFilter(int slot, List<String> values, boolean text) {

    }

    /**
     * 创建与编辑共用的可写字段集合。
     */
    record Fields(String title, String description, String priority, Person assignee, ScheduleMode mode, Dates dates,
                  Long marketId, LocalDate tradeBase, Integer startOffset, Integer endOffset, Integer dueOffset,
                  Long dimension1, Long dimension2, Long dimension3, Long dimension4, Long dimension5) {

    }

    /**
     * 状态流转参数，关闭时携带关闭时间、操作人与取消信息。
     */
    record StatusChange(long id, long version, Status status, LocalDateTime closedAt, Integer closedBy,
                        String cancelKind, String reason, LocalDateTime now) {

    }
}
