package me.lendev.flow.workbench.todo;

import static me.lendev.flow.workbench.todo.BusinessTodoCalendarConditions.noneVisible;
import static me.lendev.flow.workbench.todo.BusinessTodoCalendarRequest.invalid;
import static me.lendev.flow.workbench.todo.BusinessTodoCalendarRequest.validValue;

import com.mybatisflex.core.query.QueryCondition;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import me.lendev.flow.common.exception.ResourceNotFoundException;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarDimension.Option;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarModels.*;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarRepository.Entry;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarRepository.Scope;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarRepository.Slice;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarRequest.Page;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarRequest.Window;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarScene.ContextKind;
import me.lendev.flow.workbench.todo.BusinessTodoCalendarScene.Definition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统一校验场景、可见范围和维度后查询业务日历，空登记时保留工作台原入口。
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class BusinessTodoCalendarService {

    private static final int GRID_LIMIT = 500;
    private static final Window UNSCHEDULED = new Window(null, null);
    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9._-]{0,63}");
    private static final Pattern TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final List<StatusOption> STATUSES = List.of(new StatusOption("all", "全部"),
            new StatusOption("pending", "待办"), new StatusOption("done", "已办"),
            new StatusOption("cancelled", "已取消"));

    private final Map<String, RegisteredScene> scenes;
    private final BusinessTodoCalendarRepository repository;
    private final TodoSchedule schedule;

    BusinessTodoCalendarService(List<BusinessTodoCalendarScene> adapters, BusinessTodoCalendarRepository repository,
                                TodoSchedule schedule) {
        this.repository = repository;
        this.schedule = schedule;
        Map<String, RegisteredScene> registered = new LinkedHashMap<>();
        for (var adapter : adapters) {
            var scene = registered(adapter);
            if (registered.putIfAbsent(scene.definition().code(), scene) != null) {
                throw new IllegalArgumentException("业务日历场景编码重复 " + scene.definition().code());
            }
        }
        this.scenes = Map.copyOf(registered);
    }

    public Meta meta(String code, String contextId, int viewer) {
        var access = access(code, contextId, viewer);
        var contextRef = access.scope().context();
        var context = contextRef == null ? null : new ContextSummary(contextRef.objectId(),
                recordLabel(contextRef, access.scene().adapter().recordLabels(Set.of(contextRef), viewer)));
        return new Meta(access.scene().definition().title(), context, access.scene().defaultView(),
                STATUSES, access.scene().definition().dimensions().stream().map(BusinessTodoCalendarService::metadata).toList());
    }

    public PageResult<Option> options(String code, String contextId, String key, String keyword, List<String> values,
                                      Page page, int viewer) {
        var access = access(code, contextId, viewer);
        var dimension = access.scene().dimensions().get(key);
        if (dimension == null || dimension.search() == null
                || keyword != null && !keyword.isBlank() && !validValue(keyword.strip())) {
            throw invalid();
        }
        validateValues(dimension, values);
        return repository.options(access.scope(), dimension.search(), keyword == null ? "" : keyword.strip(), values, page);
    }

    public Calendar calendar(String code, BusinessTodoCalendarRequest request, int viewer) {
        var access = access(code, request.contextId(), viewer);
        var filters = filters(access, request.filters());
        var slice = new Slice(access.scope(), request.window(), filters);
        var counts = repository.counts(slice);
        var rows = repository.entries(slice, request.status(), 0, GRID_LIMIT + 1);
        long unscheduled = repository.total(new Slice(access.scope(), UNSCHEDULED, filters), request.status());
        boolean overflow = rows.size() > GRID_LIMIT;
        return new Calendar(overflow ? List.of() : items(rows, access.scene(), viewer), counts, unscheduled, overflow);
    }

    public PageResult<Item> items(String code, BusinessTodoCalendarRequest request, int viewer) {
        var access = access(code, request.contextId(), viewer);
        var slice = new Slice(access.scope(), request.window(), filters(access, request.filters()));
        var page = request.page();
        var rows = repository.entries(slice, request.status(), page.offset(), page.size());
        return new PageResult<>(items(rows, access.scene(), viewer), repository.total(slice, request.status()));
    }

    public Detail detail(String code, String contextId, long todoId, int viewer) {
        var access = access(code, contextId, viewer);
        var row = repository.entry(access.scope(), todoId);
        if (row == null) {
            throw new ResourceNotFoundException();
        }
        var adapter = access.scene().adapter();
        var links = adapter.links(row.businessRecord(), todoId, viewer);
        var item = items(List.of(row), access.scene(), viewer).getFirst();
        return Detail.from(item, row.getClosedAt() == null ? null : row.getClosedAt().toInstant(ZoneOffset.UTC),
                links == null ? null : safePath(links.businessUrl()), links == null ? null : safePath(links.workUrl()));
    }

    private Access access(String code, String contextId, int viewer) {
        var scene = scenes.get(code);
        if (scene == null) {
            throw new ResourceNotFoundException();
        }
        var definition = scene.definition();
        TodoAccess.permit(definition.permission());
        var context = definition.context();
        if (context == null && contextId != null || context != null && !validValue(contextId)) {
            throw invalid();
        }
        BusinessTodoRef ref = context == null ? null : new BusinessTodoRef(context.module(), context.objectType(), contextId);
        if (ref != null && !scene.adapter().canReadContext(ref, viewer)) {
            throw new ResourceNotFoundException();
        }
        return new Access(scene, new Scope(definition, ref, effective(scene.adapter().visibleTodos(viewer)), viewer));
    }

    private List<QueryCondition> filters(Access access, Map<String, List<String>> requested) {
        var conditions = new ArrayList<QueryCondition>(requested.size());
        for (var entry : requested.entrySet()) {
            var dimension = access.scene().dimensions().get(entry.getKey());
            var values = entry.getValue();
            if (dimension == null || !dimension.multiple() && values.size() > 1) {
                throw invalid();
            }
            try {
                validateValues(dimension, values);
                if (dimension.search() != null) {
                    conditions.add(repository.searchableMatch(access.scope(), dimension.search(), values));
                    continue;
                }
                Set<String> allowed = dimension.options().stream().map(Option::value).collect(Collectors.toSet());
                var visible = values.stream().filter(allowed::contains).toList();
                conditions.add(visible.isEmpty() ? noneVisible() : effective(dimension.matches().apply(visible)));
            } catch (IllegalArgumentException malformed) {
                throw invalid();
            }
        }
        return conditions;
    }

    private static void validateValues(BusinessTodoCalendarDimension dimension, List<String> values) {
        if (values.stream().anyMatch(value -> !dimension.validValue().test(value))) {
            throw invalid();
        }
    }

    private List<Item> items(List<Entry> rows, RegisteredScene scene, int viewer) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var adapter = scene.adapter();
        var records = rows.stream().map(Entry::businessRecord).collect(Collectors.toUnmodifiableSet());
        var labels = adapter.recordLabels(records, viewer);
        LocalDate today = schedule.today();
        return rows.stream().map(row -> {
            boolean overdue = !row.getStatus().closed() && row.getDueDate() != null && row.getDueDate().isBefore(today);
            return new Item(Long.toString(row.getId()), adapter.taskLabel(row.getTaskKindCode()), row.getStatus(),
                    overdue, row.getStartDate(), row.getEndDate(), row.getDueDate(), row.getAssigneeUserId(),
                    row.getAssigneeName(), row.getTaskKindCode(), recordLabel(row.businessRecord(), labels));
        }).toList();
    }

    private static String recordLabel(BusinessTodoRef businessRecord, Map<BusinessTodoRef, String> labels) {
        return labels.getOrDefault(businessRecord, businessRecord.objectId());
    }

    private static QueryCondition effective(QueryCondition condition) {
        return condition == null || !condition.checkEffective() ? noneVisible() : condition;
    }

    private static Dimension metadata(BusinessTodoCalendarDimension dimension) {
        return new Dimension(dimension.key(), dimension.label(), dimension.primary(), dimension.multiple(),
                dimension.search() != null, dimension.options());
    }

    private static RegisteredScene registered(BusinessTodoCalendarScene adapter) {
        var supplied = adapter.definition();
        var list = List.copyOf(supplied.dimensions());
        var definition = new Definition(supplied.code(), supplied.title(), supplied.permission(), supplied.recordModule(),
                supplied.recordType(), supplied.context(), list);
        var context = definition.context();
        String defaultView = adapter.defaultView();
        if (!matches(CODE, definition.code()) || !matches(CODE, definition.recordModule())
                || !matches(TYPE, definition.recordType()) || !hasText(definition.title())
                || !hasText(definition.permission()) || !Set.of("week", "month").contains(defaultView)
                || !validContext(definition, context)) {
            throw new IllegalArgumentException("业务日历场景定义无效 " + definition.code());
        }
        Map<String, BusinessTodoCalendarDimension> byKey = new LinkedHashMap<>();
        for (var dimension : list) {
            if (byKey.putIfAbsent(dimension.key(), dimension) != null) {
                throw new IllegalArgumentException("业务日历维度重复 " + dimension.key());
            }
        }
        return new RegisteredScene(adapter, definition, defaultView, Map.copyOf(byKey));
    }

    private static boolean validContext(Definition definition, BusinessTodoCalendarScene.Context context) {
        if (context == null) {
            return true;
        }
        return context.kind() != null && matches(CODE, context.module()) && matches(TYPE, context.objectType())
                && (context.kind() == ContextKind.LINK || definition.recordModule().equals(context.module())
                && definition.recordType().equals(context.objectType()));
    }

    private static boolean matches(Pattern pattern, String text) {
        return text != null && pattern.matcher(text).matches();
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private static String safePath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.indexOf('\\') >= 0) {
            return null;
        }
        try {
            String decoded = URI.create(path).getPath();
            if (decoded != null && !decoded.startsWith("//") && decoded.indexOf('\\') < 0
                    && decoded.codePoints().noneMatch(Character::isISOControl)) {
                return path;
            }
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        return null;
    }

    private record RegisteredScene(BusinessTodoCalendarScene adapter, Definition definition, String defaultView,
                                   Map<String, BusinessTodoCalendarDimension> dimensions) {
    }

    private record Access(RegisteredScene scene, Scope scope) {
    }
}
