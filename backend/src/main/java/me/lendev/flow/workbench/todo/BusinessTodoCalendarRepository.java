package me.lendev.flow.workbench.todo;

import static com.mybatisflex.core.query.QueryMethods.bracket;
import static com.mybatisflex.core.query.QueryMethods.case_;
import static com.mybatisflex.core.query.QueryMethods.coalesce;
import static com.mybatisflex.core.query.QueryMethods.column;
import static com.mybatisflex.core.query.QueryMethods.count;
import static com.mybatisflex.core.query.QueryMethods.instr;
import static com.mybatisflex.core.query.QueryMethods.lower;
import static com.mybatisflex.core.query.QueryMethods.max;
import static com.mybatisflex.core.query.QueryMethods.min;
import static com.mybatisflex.core.query.QueryMethods.null_;
import static com.mybatisflex.core.query.QueryMethods.number;
import static me.lendev.flow.workbench.todo.BusinessTodoCalendarConditions.related;
import static me.lendev.flow.workbench.todo.entity.table.TodoBusinessContextTableDef.TODO_BUSINESS_CONTEXT;
import static me.lendev.flow.workbench.todo.entity.table.TodoInstanceTableDef.TODO_INSTANCE;

import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryCondition;
import com.mybatisflex.core.query.QueryWrapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarDimension.Option;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarDimension.Search;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarModels.Counts;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarModels.PageResult;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarRequest.Selection;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarRequest.Window;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarScene.ContextKind;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarScene.Definition;
import me.lendev.flow.workbench.todo.TodoModels.Status;
import me.lendev.flow.workbench.todo.mapper.TodoMapper;
import org.springframework.stereotype.Repository;

/**
 * 从受授权的待办集合读取日历、计数、分页和筛选选项。
 */
@Repository
@RequiredArgsConstructor
class BusinessTodoCalendarRepository {

    private static final List<Status> OPEN = List.of(Status.OPEN, Status.IN_PROGRESS);
    private static final String OPTION_ROWS = "calendar_option_rows";
    private final TodoMapper mapper;

    record Scope(Definition definition, BusinessTodoRef context, QueryCondition visible, int viewer) {
    }

    record Slice(Scope scope, Window window, List<QueryCondition> filters) {
    }

    List<Entry> entries(Slice slice, Selection selection, long offset, int size) {
        var query = entryColumns(filtered(slice).and(status(selection)));
        var window = slice.window();
        if (!window.unscheduled() && !window.from().equals(window.to())) {
            QueryColumn date = coalesce(TODO_INSTANCE.START_DATE, TODO_INSTANCE.DUE_DATE);
            // 区间首日可见的条目以相同日期排序，再以 ID 解决并列。
            query.orderBy(case_().when(date.le(window.from())).then(null_()).else_(date).end().asc());
        }
        return mapper.selectListByQueryAs(query.orderBy(TODO_INSTANCE.ID.asc()).limit(offset, size), Entry.class);
    }

    Entry entry(Scope scope, long id) {
        return mapper.selectOneByQueryAs(entryColumns(scoped(scope)).and(TODO_INSTANCE.ID.eq(id)), Entry.class);
    }

    Counts counts(Slice slice) {
        var rows = mapper.selectListByQueryAs(filtered(slice).select(TODO_INSTANCE.STATUS, count().as("amount"))
                .groupBy(TODO_INSTANCE.STATUS), StatusCount.class);
        long pending = 0;
        long done = 0;
        long cancelled = 0;
        for (var row : rows) {
            switch (row.status) {
                case OPEN, IN_PROGRESS -> pending += row.amount;
                case DONE -> done = row.amount;
                case CANCELLED -> cancelled = row.amount;
            }
        }
        return new Counts(pending + done, pending, done, cancelled);
    }

    long total(Slice slice, Selection selection) {
        return mapper.selectCountByQuery(filtered(slice).and(status(selection)));
    }

    PageResult<Option> options(Scope scope, Search search, String keyword, List<String> values,
                               BusinessTodoCalendarRequest.Page page) {
        var options = optionRows(scope, search, keyword, values);
        long total = mapper.selectCountByQuery(QueryWrapper.create().from(options.clone()).as(OPTION_ROWS));
        var rows = mapper.selectListByQueryAs(options.orderBy(min(search.label()).asc(), search.value().asc())
                .limit(page.offset(), page.size()), OptionRow.class);
        return new PageResult<>(rows.stream().map(OptionRow::option).toList(), total);
    }

    QueryCondition searchableMatch(Scope scope, Search search, List<String> values) {
        // 选项按本任务的可见连接匹配，即使同一选项在另一条任务上可见也不会扩大本条结果。
        var matches = optionSource(scope, search).select(TODO_INSTANCE.ID).and(search.value().in(values));
        return TODO_INSTANCE.ID.in(matches);
    }

    private static QueryWrapper optionRows(Scope scope, Search search, String keyword, List<String> values) {
        var query = optionSource(scope, search);
        var value = search.value();
        var label = search.label();
        var enabled = search.enabled() == null ? number(1)
                : max(case_().when(search.enabled().eq(true)).then(number(1)).else_(number(0)).end());
        query.select(value.as("optionValue"), min(label).as("optionLabel"), enabled.as("optionEnabled"))
                .groupBy(value);
        if (!keyword.isEmpty()) {
            query.and(instr(lower(label), column("?", (Object) keyword.toLowerCase(Locale.ROOT))).gt(0));
        }
        if (!values.isEmpty()) {
            query.and(value.in(values));
        }
        return query;
    }

    private static QueryWrapper optionSource(Scope scope, Search search) {
        var query = scoped(scope);
        if (search.joins() != null) {
            for (var join : search.joins().apply(scope.viewer())) {
                query.innerJoin(join.table()).on(join.on().clone());
            }
        }
        return query.and(search.value().isNotNull()).and(search.label().isNotNull());
    }

    private static QueryWrapper filtered(Slice slice) {
        var query = scoped(slice.scope());
        for (var condition : slice.filters()) {
            query.and(bracket(condition.clone()));
        }
        return query.and(bracket(dates(slice.window())));
    }

    private static QueryWrapper scoped(Scope scope) {
        var definition = scope.definition();
        var query = QueryWrapper.create().from(TODO_INSTANCE)
                .innerJoin(TODO_BUSINESS_CONTEXT).on(TODO_BUSINESS_CONTEXT.TODO_ID.eq(TODO_INSTANCE.ID))
                .where(TODO_BUSINESS_CONTEXT.RECORD_MODULE.eq(definition.recordModule()))
                .and(TODO_BUSINESS_CONTEXT.RECORD_TYPE.eq(definition.recordType()))
                .and(bracket(scope.visible().clone()));
        if (scope.context() != null) {
            var context = scope.context();
            if (ContextKind.RECORD == definition.context().kind()) {
                query.and(TODO_BUSINESS_CONTEXT.RECORD_ID.eq(context.objectId()));
            } else {
                query.and(related(context.module(), context.objectType(), List.of(context.objectId())));
            }
        }
        return query;
    }

    private static QueryCondition dates(Window window) {
        if (window.unscheduled()) {
            return TODO_INSTANCE.START_DATE.isNull().and(TODO_INSTANCE.END_DATE.isNull())
                    .and(TODO_INSTANCE.DUE_DATE.isNull());
        }
        return TODO_INSTANCE.START_DATE.le(window.to()).and(TODO_INSTANCE.END_DATE.ge(window.from()))
                .or(TODO_INSTANCE.START_DATE.isNull().and(TODO_INSTANCE.DUE_DATE.between(window.from(), window.to())));
    }

    private static QueryCondition status(Selection status) {
        return switch (status) {
            case ALL -> TODO_INSTANCE.STATUS.in(Status.OPEN, Status.IN_PROGRESS, Status.DONE);
            case PENDING -> TODO_INSTANCE.STATUS.in(OPEN);
            case DONE -> TODO_INSTANCE.STATUS.eq(Status.DONE);
            case CANCELLED -> TODO_INSTANCE.STATUS.eq(Status.CANCELLED);
        };
    }

    private static QueryWrapper entryColumns(QueryWrapper query) {
        return query.select(TODO_INSTANCE.ID, TODO_INSTANCE.STATUS, TODO_INSTANCE.START_DATE, TODO_INSTANCE.END_DATE,
                TODO_INSTANCE.DUE_DATE, TODO_INSTANCE.ASSIGNEE_USER_ID, TODO_INSTANCE.ASSIGNEE_NAME,
                TODO_INSTANCE.CLOSED_AT, TODO_BUSINESS_CONTEXT.RECORD_MODULE, TODO_BUSINESS_CONTEXT.RECORD_TYPE,
                TODO_BUSINESS_CONTEXT.RECORD_ID, TODO_BUSINESS_CONTEXT.TASK_KIND_CODE);
    }

    @Getter
    @Setter
    public static class Entry {
        private long id;
        private Status status;
        private LocalDate startDate;
        private LocalDate endDate;
        private LocalDate dueDate;
        private int assigneeUserId;
        private String assigneeName;
        private LocalDateTime closedAt;
        private String recordModule;
        private String recordType;
        private String recordId;
        private String taskKindCode;

        BusinessTodoRef businessRecord() {
            return new BusinessTodoRef(recordModule, recordType, recordId);
        }
    }

    @Setter
    public static class StatusCount {
        private Status status;
        private long amount;
    }

    @Getter
    @Setter
    public static class OptionRow {
        private String optionValue;
        private String optionLabel;
        private boolean optionEnabled;

        Option option() {
            return new Option(optionValue, optionLabel, optionEnabled);
        }
    }
}
