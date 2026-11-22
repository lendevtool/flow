package me.lendev.flow.workbench.todo;

import static me.lendev.flow.workbench.todo.TodoModels.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.workbench.todo.entity.TodoInstance;
import me.lendev.flow.workbench.todo.mapper.TodoMapper;
import me.lendev.flow.workbench.todo.mapper.TodoMapper.Fields;
import me.lendev.flow.workbench.todo.mapper.TodoMapper.Filters;
import me.lendev.flow.workbench.todo.mapper.TodoMapper.PersonalFilter;
import me.lendev.flow.workbench.todo.mapper.TodoMapper.StatusChange;
import me.lendev.flow.workbench.todo.mapper.TodoMapper.TodoRow;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

/**
 * 待办实例读写、行锁和按同一条件查询列表及统计。
 */
@Repository
@RequiredArgsConstructor
@Slf4j
class TodoRepository {

    private final TodoMapper mapper;

    /**
     * 请求键命中的已有待办及其内容摘要，用于幂等回放比对。
     */
    record Existing(Todo todo, String hash) {

    }

    /**
     * 周期期次标识：规则 ID、规则修订号、锚点与业务发生日期。
     */
    record Period(Long id, Integer revision, LocalDate base, LocalDate occurrence) {

    }

    /**
     * 创建上下文。scope 与请求键共同构成幂等唯一约束，hash 为内容摘要。
     */
    record Creation(Origin origin, Integer ownerId, String ownerName, Source source, String scope, String hash,
                    Period period, Map<Integer, String> personalDimensions) {

    }

    /**
     * 维度分组统计行，key 为 null 表示未填写。
     */
    record Aggregate(String key, long total, long open, long done, long overdue) {

    }

    /**
     * 普通读取待办，不存在时返回 empty。
     */
    Optional<Todo> find(long id) {
        return Optional.ofNullable(mapper.find(id)).map(TodoRepository::read);
    }

    /**
     * 以 FOR UPDATE 锁定待办行后读取，须在写事务内调用。
     */
    Optional<Todo> lock(long id) {
        return Optional.ofNullable(mapper.lock(id)).map(TodoRepository::read);
    }

    /**
     * 锁定周期规则行，保证与规则变更的加锁顺序一致。
     */
    void lockSeries(long id) {
        mapper.lockSeries(id);
    }

    /**
     * 按幂等范围与请求键查询已有待办。
     */
    Optional<Existing> byRequest(String scope, String key) {
        return Optional.ofNullable(mapper.byRequest(scope, key))
                .map(row -> new Existing(read(row), row.getRequestHash()));
    }

    /**
     * 按规则、修订号与锚点查询周期实例，用于生成幂等。
     */
    Optional<Todo> occurrence(long seriesId, int revision, LocalDate baseDate) {
        return Optional.ofNullable(mapper.occurrence(seriesId, revision, baseDate)).map(TodoRepository::read);
    }

    /**
     * 插入待办实例并返回自增 ID，初始状态 OPEN、版本 0。
     */
    long insert(Write request, Person person, TodoSchedule.Resolved schedule, Creation creation, Long departmentId,
                LocalDateTime now) {
        var row = new TodoInstance();
        TodoMapper.applyFields(row, fields(request, person, schedule));
        row.setOrigin(creation.origin());
        row.setOwnerUserId(creation.ownerId());
        row.setOwnerName(creation.ownerName());
        row.setDepartmentId(departmentId);
        var source = creation.source();
        if (source != null) {
            row.setSourceModule(source.module());
            row.setBusinessType(source.type());
            row.setBusinessId(source.id());
        }
        row.setRequestScope(creation.scope());
        row.setRequestKey(request.requestKey());
        row.setRequestHash(creation.hash());
        var period = creation.period();
        if (period != null) {
            row.setSeriesId(period.id());
            row.setSeriesRevision(period.revision());
            row.setSeriesBaseDate(period.base());
            row.setOccurrenceDate(period.occurrence());
        }
        row.setStatus(Status.OPEN);
        row.setOccurrenceEdited(false);
        row.setVersion(0L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        mapper.insertSelective(row);
        return Objects.requireNonNull(row.getId());
    }

    /**
     * 按版本号条件更新内容，周期实例同时标记已单独编辑。
     */
    void edit(Todo before, Write request, Person person, TodoSchedule.Resolved schedule, LocalDateTime now) {
        checked(mapper.edit(before.id(), before.version(), fields(request, person, schedule),
                before.origin() == Origin.SERIES, now));
    }

    /**
     * 按版本号条件流转状态，关闭时记录关闭时间、操作人与取消信息。
     */
    void transition(Todo before, Status status, Integer actor, String cancelKind, String reason, LocalDateTime now) {
        checked(mapper.transition(new StatusChange(before.id(), before.version(), status, status.closed() ? now : null,
                status.closed() ? actor : null, cancelKind, reason, now)));
    }

    /**
     * 按版本号条件改派负责人。
     */
    void assign(Todo before, Person person, LocalDateTime now) {
        checked(mapper.assign(before.id(), before.version(), person, now));
    }

    /**
     * 查询用户快照，active 为 true 时仅匹配启用账号，lock 为 true 时加行锁。
     */
    Optional<Person> person(int id, boolean active, boolean lock) {
        return Optional.ofNullable(mapper.person(id, !active, lock));
    }

    /**
     * 查询用户主部门 ID，未设置时返回 null。
     */
    Long primaryDepartmentId(int userId) {
        return mapper.primaryDepartmentId(userId);
    }

    /**
     * 查询可改派的启用用户列表。
     */
    List<Person> people() {
        return mapper.people();
    }

    /**
     * 查询启用市场参数项 ID，不存在时返回 null。
     */
    Long marketId(String code) {
        return DataAccessUtils.nullableSingleResult(mapper.marketIds(code));
    }

    /**
     * 按同一筛选条件统计各范围（全部/我的/我创建/已完成）数量。
     */
    Map<String, Long> counts(TodoQuery query, int viewer, LocalDate today, Set<Integer> textSlots) {
        var counts = mapper.counts(filters(query, viewer, today, textSlots));
        return Map.of("all", counts.getAllCount(), "mine", counts.getMineCount(),
                "created", counts.getCreatedCount(), "done", counts.getDoneCount());
    }

    List<Todo> page(TodoQuery query, int viewer, LocalDate today, Set<Integer> textSlots) {
        long offset = ((long) query.page() - 1) * query.size();
        return mapper.page(filters(query, viewer, today, textSlots), query.size(), offset)
                .stream().map(TodoRepository::read).toList();
    }

    List<CalendarTodo> calendar(TodoQuery query, int viewer, LocalDate today, Set<Integer> textSlots) {
        return mapper.calendar(filters(query, viewer, today, textSlots), query.includeCompleted());
    }

    List<Aggregate> statistics(TodoQuery query, int viewer, LocalDate today, boolean personal, int slot,
                               Set<Integer> textSlots) {
        var filters = filters(query, viewer, today, textSlots);
        var rows = personal ? mapper.personalStatistics(filters, slot) : mapper.publicStatistics(filters, slot);
        return rows.stream().map(row -> new Aggregate(row.getGroupKey(), row.getTotal(), row.getOpened(), row.getDone(),
                row.getOverdue())).toList();
    }

    private static Filters filters(TodoQuery query, int viewer, LocalDate today, Set<Integer> textSlots) {
        String escaped = query.keyword().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        var personalFilters = query.personalFilters().entrySet().stream()
                .map(entry -> new PersonalFilter(entry.getKey(), entry.getValue(), textSlots.contains(entry.getKey())))
                .toList();
        return new Filters(viewer, today, query.scope(), escaped.isEmpty() ? null : "%" + escaped + "%",
                query.origin(), query.status(), query.dateField().equals("plan"),
                query.calendarJump() && query.includeCompleted(), !query.calendarJump() || query.includeCompleted(),
                query.from(), query.to(), query.publicFilters(), personalFilters);
    }

    private static Fields fields(Write request, Person person, TodoSchedule.Resolved schedule) {
        var dimensions = request.dimensions();
        return new Fields(request.title(), request.description(), request.priority(), person, TodoSchedule.mode(request),
                schedule.dates(), schedule.marketItemId(), request.tradeBaseDate(), request.startTradeOffset(),
                request.endTradeOffset(), request.dueTradeOffset(), dimensions.get(1), dimensions.get(2),
                dimensions.get(3), dimensions.get(4), dimensions.get(5));
    }

    private static Todo read(TodoRow row) {
        Map<Integer, Long> dimensions = new LinkedHashMap<>();
        Long[] values = {row.getDimension1ItemId(), row.getDimension2ItemId(), row.getDimension3ItemId(),
                row.getDimension4ItemId(), row.getDimension5ItemId()};
        for (int index = 0; index < values.length; index++) {
            if (values[index] != null) {
                dimensions.put(index + 1, values[index]);
            }
        }
        return new Todo(row.getId(), row.getTitle(), row.getDescription(), row.getOrigin(), row.getPriority(), row.getStatus(),
                row.getOwnerUserId(), row.getOwnerName(), row.getDepartmentId(), row.getAssigneeUserId(), row.getAssigneeName(),
                row.getSourceModule(),
                row.getBusinessType(), row.getBusinessId(), row.getRequestKey(), row.getScheduleMode(), row.getStartDate(),
                row.getEndDate(), row.getDueDate(), row.getMarketCode(), row.getTradeBaseDate(), row.getStartTradeOffset(),
                row.getEndTradeOffset(), row.getDueTradeOffset(), Map.copyOf(dimensions), Map.of(), row.getSeriesId(),
                row.getVersion(), row.getCreatedAt(), row.getClosedAt(), List.of(), null, null);
    }

    /**
     * 条件更新必须恰好命中一行，否则说明版本已变化，抛出冲突。
     */
    private static void checked(int changed) {
        if (changed != 1) {
            log.info("待办条件更新未命中，版本已变化，影响行数={}", changed);
            throw new TodoConflictException("error.todo.version");
        }
    }
}
