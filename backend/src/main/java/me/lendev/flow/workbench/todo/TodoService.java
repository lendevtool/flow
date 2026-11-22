package me.lendev.flow.workbench.todo;

import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO_ASSIGN;
import static me.lendev.flow.workbench.todo.TodoModels.*;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.AuditSystemContext;
import me.lendev.flow.common.exception.BadRequestException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * 待办查询、命令分发及业务模块接入。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TodoService {

    private static final String WORKFLOW = "workflow";
    private static final String PROCESS_INSTANCE = "PROCESS_INSTANCE";
    private static final String WORKFLOW_SCOPE = "MODULE:" + WORKFLOW;
    private static final String INVALID_SOURCE = "error.todo.source";
    private static final String INVALID_REQUEST = "error.todo.request";
    private final TodoRepository repository;
    private final TodoMutations mutations;
    private final TodoSchedule schedule;
    private final TodoPersonalDimensions personal;
    private final TodoPublicDimensions publicDimensions;
    private final ObjectMapper json;
    private final List<TodoBusinessSource> businessSources;

    /**
     * 分页查询待办并附带各范围计数与个人标记。
     *
     * @param query  筛选与分页条件
     * @param viewer 当前登录人 ID，用于可见性与个人标记
     * @return 当前页记录、当前范围总数及全部范围计数
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TodoPage list(TodoQuery query, int viewer) {
        LocalDate today = schedule.today();
        var textSlots = textSlots(query, definitionsForFilters(query, viewer));
        var counts = repository.counts(query, viewer, today, textSlots);
        var records = decorate(repository.page(query, viewer, today, textSlots), viewer);
        return new TodoPage(records, counts.get(query.scope()), counts);
    }

    /**
     * 日历视图查询，日期区间必须完整且跨度小于 42 天。
     *
     * @throws BadRequestException 区间缺失或跨度超限
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<CalendarTodo> calendar(TodoQuery query, int viewer) {
        if (query.from() == null || query.to() == null || ChronoUnit.DAYS.between(query.from(), query.to()) >= 42) {
            throw BadRequestException.localized("error.todo.calendar-range");
        }
        var textSlots = textSlots(query, definitionsForFilters(query, viewer));
        return repository.calendar(query, viewer, schedule.today(), textSlots);
    }

    /**
     * 按公共或个人维度槽位分组统计，按总数倒序返回。
     *
     * @param groupBy 形如 public:1 或 personal:2 的分组标识
     * @throws BadRequestException 分组标识不合法
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Statistic> statistics(TodoQuery query, String groupBy, int viewer) {
        if (groupBy == null || !groupBy.matches("(public|personal):[1-5]")) {
            throw BadRequestException.localized("error.todo.query");
        }
        boolean privateGroup = groupBy.startsWith("personal");
        int slot = Character.digit(groupBy.charAt(groupBy.length() - 1), 10);
        var personalDefinitions = privateGroup ? personal.list(viewer) : definitionsForFilters(query, viewer);
        Map<String, String> names = privateGroup
                ? personalDefinitions.stream().filter(item -> item.slot() == slot).findFirst()
                .map(item -> item.options().stream().collect(Collectors.toMap(
                        PersonalOption::key, PersonalOption::name)))
                .orElseGet(Map::of)
                : publicDimensions.list().stream().filter(item -> item.slot() == slot).findFirst()
                .map(item -> item.options().stream().collect(java.util.stream.Collectors.toMap(
                        option -> Long.toString(option.id()), TodoPublicDimensions.Option::name)))
                .orElseGet(Map::of);
        return repository.statistics(query, viewer, schedule.today(), privateGroup, slot,
                        textSlots(query, personalDefinitions)).stream()
                .map(row -> new Statistic(row.key(), row.key() == null ? "未填写" : names.getOrDefault(row.key(), row.key()),
                        row.total(), row.open(), row.done(), row.overdue()))
                .sorted(Comparator.comparingLong(Statistic::total).reversed().thenComparing(Statistic::name)).toList();
    }

    private List<PersonalDimension> definitionsForFilters(TodoQuery query, int viewer) {
        return query.personalFilters().isEmpty() ? List.of() : personal.list(viewer);
    }

    private static Set<Integer> textSlots(TodoQuery query, List<PersonalDimension> definitions) {
        return definitions.stream().filter(field -> field.options().isEmpty()
                        && query.personalFilters().containsKey(field.slot()))
                .map(PersonalDimension::slot).collect(Collectors.toSet());
    }

    /**
     * 查询待办详情，附带个人标记、可用操作与业务跳转链接。
     *
     * @throws TodoNotFoundException 待办不存在或当前人不是负责人/发起人
     */
    public Todo detail(long id, int viewer) {
        var todo = mutations.get(id);
        requireVisible(todo, viewer);
        var tags = personal.values(viewer, List.of(id)).getOrDefault(id, Map.of());
        return decorate(todo, tags, viewer, true);
    }

    /**
     * 手动创建待办，以请求键幂等；改派他人需待办改派权限。
     */
    Todo create(Write input, int viewer) {
        Write request = normalize(input, viewer);
        requireKey(request.requestKey());
        if (request.assigneeUserId() != viewer) {
            TodoAccess.permit(WORKBENCH_TODO_ASSIGN);
        }
        var tags = TodoPersonalDimensions.normalizeCreateTags(input.personalDimensions());
        String hash = hash(request, null, tags);
        String scope = "USER:" + viewer;
        var owner = requireUser(viewer);
        Todo todo = createOnce(request, new TodoRepository.Creation(Origin.MANUAL, viewer, owner.name(),
                null, scope, hash, null, tags));
        log.info("手动待办创建成功，待办 ID={}，负责人 ID={}，创建人 ID={}", todo.id(), todo.assigneeUserId(), viewer);
        return decorate(List.of(todo), viewer).getFirst();
    }

    /**
     * 由登记的同应用业务模块创建待办，重试须沿用原请求键。
     * 请求键已存在且内容摘要一致时幂等返回原待办。
     *
     * @param source 业务来源标识，module 必须已登记且不能为 workflow
     * @param input  待办内容，必须携带请求键
     * @return 新建或幂等命中的待办
     * @throws BadRequestException   来源未登记、无权创建或参数不合法
     * @throws TodoConflictException 请求键重复但内容不一致
     */
    public Todo createFromBusiness(Source source, Write input) {
        if (source == null || !validModule(source.module()) || WORKFLOW.equals(source.module())
                || invalidText(source.type(), 64) || invalidText(source.id(), 128)) {
            throw BadRequestException.localized(INVALID_SOURCE);
        }
        Write request = normalize(input, null);
        requireKey(request.requestKey());
        var registration = businessSources.stream().filter(item -> item.module().equals(source.module()))
                .findFirst().orElseThrow(() -> BadRequestException.localized(INVALID_SOURCE));
        if (!registration.mayCreate(source, request.assigneeUserId())) {
            throw BadRequestException.localized(INVALID_SOURCE);
        }
        String scope = "MODULE:" + source.module();
        String hash = hash(request, source);
        var prior = repository.byRequest(scope, request.requestKey());
        if (prior.isPresent()) {
            return replay(prior.get(), hash);
        }
        String ownerName = source.ownerId() == null ? null : requireUser(source.ownerId()).name();
        try (var actor = AuditSystemContext.open("业务模块 " + source.module())) {
            Todo todo = createOnce(request, new TodoRepository.Creation(Origin.BUSINESS, source.ownerId(), ownerName,
                    source, scope, hash, null, Map.of()));
            log.info("业务模块待办创建成功，模块={}，业务类型={}，业务 ID={}，待办 ID={}", source.module(), source.type(),
                    source.id(), todo.id());
            return todo;
        }
    }

    private Todo createOnce(Write request, TodoRepository.Creation creation) {
        var prior = repository.byRequest(creation.scope(), request.requestKey());
        if (prior.isPresent()) {
            return replay(prior.get(), creation.hash());
        }
        boolean joining = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            return (AuditParticipants.active() ? mutations.createInAggregate(request, creation)
                    : mutations.create(request, creation)).after();
        } catch (DuplicateKeyException duplicate) {
            if (joining) {
                throw new TodoConflictException("error.todo.request-key");
            }
            return repository.byRequest(creation.scope(), request.requestKey())
                    .map(row -> replay(row, creation.hash())).orElseThrow(() -> duplicate);
        }
    }

    /**
     * 周期规则期次，以规则、修订号和基准日作为幂等标识。
     */
    public record SeriesOccurrence(long seriesId, int revision, LocalDate anchor, LocalDate occurrence) {

    }

    /**
     * 为已锁定的周期规则写入一个期次，复用调用方的业务及审计事务。
     * 同一规则、修订号与锚点已存在实例时幂等返回。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Todo createSeriesOccurrence(SeriesOccurrence period, int ownerId, String ownerName,
                                       Write input, Dates dates) {
        var prior = repository.occurrence(period.seriesId(), period.revision(), period.anchor());
        if (prior.isPresent()) {
            return prior.get();
        }
        Write request = normalize(withKey(input, null), null);
        Long market = request.marketCode() == null ? null : repository.marketId(request.marketCode());
        var creation = new TodoRepository.Creation(Origin.SERIES, ownerId, ownerName, null,
                null, null, new TodoRepository.Period(period.seriesId(), period.revision(), period.anchor(),
                period.occurrence()), Map.of());
        Todo todo = mutations.insert(request, creation, new TodoSchedule.Resolved(dates, market), true);
        log.info("周期待办实例生成，规则 ID={}，修订号={}，锚点={}，待办 ID={}", period.seriesId(), period.revision(),
                period.anchor(), todo.id());
        return todo;
    }

    /**
     * 为流程节点创建待办，当前事务必须由流程服务开启。
     * 激活标识已存在且内容摘要一致时幂等返回原待办。
     *
     * @param flowId        流程实例 ID
     * @param activationKey 节点激活标识，作为幂等请求键
     * @throws TodoConflictException 激活标识重复但内容不一致
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Todo createFlowNode(long flowId, String activationKey, Integer ownerId, String ownerName, Write input) {
        requireKey(activationKey);
        Write request = normalize(withKey(input, activationKey), null);
        var source = new Source(WORKFLOW, PROCESS_INSTANCE, Long.toString(flowId), ownerId);
        String hash = hash(request, source);
        var prior = repository.byRequest(WORKFLOW_SCOPE, activationKey);
        if (prior.isPresent()) {
            return replay(prior.get(), hash);
        }
        var creation = new TodoRepository.Creation(Origin.FLOW, ownerId, ownerName, source, WORKFLOW_SCOPE, hash,
                null, Map.of());
        Todo todo = mutations.insert(request, creation, schedule.resolve(request), true);
        log.info("流程节点待办创建，流程实例 ID={}，激活标识={}，待办 ID={}", flowId, activationKey, todo.id());
        return todo;
    }

    /**
     * 关闭指定流程实例和激活标识所属的待办。
     * 已处于目标状态时幂等返回，其他已关闭状态视为冲突。
     *
     * @param target 目标状态，必须为已关闭状态（DONE/CANCELLED）
     * @throws TodoConflictException 待办已关闭且与目标状态不同
     * @throws TodoNotFoundException 待办不存在或与流程实例、激活标识不匹配
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Todo closeFlowNode(long flowId, String activationKey, long todoId, Status target, int actor, String reason) {
        if (target == null || !target.closed()) {
            throw BadRequestException.localized(INVALID_REQUEST);
        }
        var before = lockFlow(flowId, activationKey, todoId);
        if (before.status() == target) {
            return before;
        }
        if (before.status().closed()) {
            throw new TodoConflictException("error.todo.status");
        }
        boolean cancelled = target == Status.CANCELLED;
        repository.transition(before, target, actor, cancelled ? "FLOW_CANCEL" : null,
                cancelled ? TodoMutations.reason(reason) : null, schedule.now());
        log.info("流程节点待办关闭，流程实例 ID={}，待办 ID={}，目标状态={}", flowId, todoId, target);
        return mutations.get(todoId);
    }

    /**
     * 在流程事务中锁定并读取节点待办，供聚合审计取得变更前快照。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Todo readFlowNode(long flowId, String activationKey, long todoId) {
        return lockFlow(flowId, activationKey, todoId);
    }

    /**
     * 由流程服务同步节点的当前负责人，负责人未变化时不产生写入。
     *
     * @throws TodoConflictException 待办已关闭
     * @throws TodoAssigneeException 新负责人不存在或已停用
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Todo assignFlowNode(long flowId, String activationKey, long todoId, int assigneeId) {
        var before = lockFlow(flowId, activationKey, todoId);
        if (before.status().closed()) {
            throw new TodoConflictException("error.todo.status");
        }
        var person = requireActiveUser(assigneeId);
        if (before.assigneeUserId() != person.id()) {
            repository.assign(before, person, schedule.now());
            log.info("流程节点待办负责人同步，待办 ID={}，新负责人 ID={}", todoId, person.id());
        }
        return mutations.get(todoId);
    }

    /**
     * 校验负责人仍为有效账号并取得姓名快照。
     */
    public Person requireActiveUser(int id) {
        return mutations.activeUser(id);
    }

    /**
     * 取得已存在账号的姓名快照。
     */
    public Person requireUser(int id) {
        return repository.person(id, false, false).orElseThrow(TodoNotFoundException::new);
    }

    List<Person> assignees() {
        return repository.people();
    }

    Todo withCurrentViewer(Todo todo, int viewer) {
        return decorate(List.of(todo), viewer).getFirst();
    }

    private Todo lockFlow(long flowId, String activationKey, long todoId) {
        var row = repository.lock(todoId).orElseThrow(TodoNotFoundException::new);
        if (row.origin() != Origin.FLOW || !WORKFLOW.equals(row.sourceModule())
                || !PROCESS_INSTANCE.equals(row.businessType()) || !Long.toString(flowId).equals(row.businessId())
                || !Objects.equals(activationKey, row.requestKey())) {
            throw new TodoNotFoundException();
        }
        return row;
    }

    private List<Todo> decorate(List<Todo> todos, int viewer) {
        var tags = personal.values(viewer, todos.stream().map(Todo::id).toList());
        return todos.stream().map(todo -> decorate(todo, tags.getOrDefault(todo.id(), Map.of()), viewer, false))
                .toList();
    }

    private Todo decorate(Todo todo, Map<Integer, String> tags, int viewer, boolean includeLink) {
        var registration = businessSources.stream().filter(item -> item.module().equals(todo.sourceModule()))
                .findFirst().orElse(null);
        String label = registration == null ? todo.sourceModule() : registration.displayName();
        String url = null;
        if (includeLink && isRoleWorkflow(todo)) {
            url = "/workbench/workflow-task/" + todo.businessId() + "?mode=task&todo=" + todo.id();
        }
        if (includeLink && registration != null) {
            var source = new Source(todo.sourceModule(), todo.businessType(), todo.businessId(), todo.ownerUserId());
            String path = registration.openPath(source, viewer);
            if (path != null && path.startsWith("/") && !path.startsWith("//") && path.indexOf('\\') < 0
                    && path.codePoints().noneMatch(Character::isISOControl)) {
                url = path;
            }
        }
        return todo.forViewer(tags, allowed(todo, viewer), label, url);
    }

    static void requireVisible(Todo todo, int viewer) {
        if (todo.assigneeUserId() != viewer && !Objects.equals(todo.ownerUserId(), viewer)) {
            throw new TodoNotFoundException();
        }
    }

    private static boolean isRoleWorkflow(Todo todo) {
        return todo.origin() == Origin.FLOW && todo.requestKey() != null && todo.requestKey().startsWith("v2:");
    }

    static List<String> allowed(Todo todo, int viewer) {
        if (todo.status().closed()) {
            return List.of();
        }
        boolean assigned = todo.assigneeUserId() == viewer;
        if (todo.origin() == Origin.FLOW) {
            return assigned && !isRoleWorkflow(todo) ? List.of("complete") : List.of();
        }
        List<String> actions = new ArrayList<>(5);
        if (assigned) {
            if (todo.status() == Status.OPEN) {
                actions.add("start");
            }
            actions.add("complete");
        }
        if (Objects.equals(todo.ownerUserId(), viewer)) {
            actions.add("edit");
            actions.add("cancel");
            if (TodoAccess.can(WORKBENCH_TODO_ASSIGN)) {
                actions.add("assign");
            }
        }
        return List.copyOf(actions);
    }

    /**
     * 幂等回放：请求键命中且内容摘要一致时返回原待办，不一致视为冲突。
     *
     * @throws TodoConflictException 相同请求键携带了不同内容
     */
    static Todo replay(TodoRepository.Existing existing, String hash) {
        if (!Objects.equals(existing.hash(), hash)) {
            log.info("待办幂等回放失败，请求键相同但内容不一致，待办 ID={}", existing.todo().id());
            throw new TodoConflictException("error.todo.request-key");
        }
        return existing.todo();
    }

    private String hash(Write request, Source source) {
        return hash(request, source, Map.of());
    }

    private String hash(Write request, Source source, Map<Integer, String> tags) {
        return DigestUtil.sha256Hex(json.writeValueAsString(new Identity(content(request), source, tags)));
    }

    // 省略空标记，保持原有无标记请求的幂等摘要。
    private record Identity(Write content, Source source,
                            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<Integer, String> personalDimensions) {

    }

    static Write content(Write request) {
        return new Write(null, request.title(), request.description(), request.assigneeUserId(), request.priority(),
                request.scheduleMode(), request.startDate(), request.endDate(), request.dueDate(), request.marketCode(),
                request.tradeBaseDate(), request.startTradeOffset(), request.endTradeOffset(), request.dueTradeOffset(),
                request.dimensions(), null, null);
    }

    private static Write withKey(Write request, String key) {
        return new Write(key, request.title(), request.description(), request.assigneeUserId(), request.priority(),
                request.scheduleMode(), request.startDate(), request.endDate(), request.dueDate(), request.marketCode(),
                request.tradeBaseDate(), request.startTradeOffset(), request.endTradeOffset(), request.dueTradeOffset(),
                request.dimensions(), request.preview(), request.version());
    }

    static Write normalize(Write input, Integer fallbackAssignee) {
        if (invalidText(input.title(), 200) || input.description() != null && input.description().length() > 10000) {
            throw BadRequestException.localized(INVALID_REQUEST);
        }
        Integer assignee = input.assigneeUserId() == null ? fallbackAssignee : input.assigneeUserId();
        if (assignee == null || assignee < 1) {
            throw new TodoAssigneeException();
        }
        String priority = input.priority() == null ? Priority.NORMAL.name() : input.priority();
        try {
            Priority.valueOf(priority);
        } catch (IllegalArgumentException invalid) {
            throw BadRequestException.localized(INVALID_REQUEST);
        }
        var dimensions = normalizeDimensions(input.dimensions());
        String market = input.marketCode();
        market = market == null || market.isBlank() ? null : market.strip().toUpperCase(Locale.ROOT);
        String description = input.description() == null || input.description().isBlank() ? null : input.description();
        return new Write(input.requestKey(), input.title().strip(), description, assignee, priority,
                TodoSchedule.mode(input).name(), input.startDate(), input.endDate(), input.dueDate(), market,
                input.tradeBaseDate(), input.startTradeOffset(), input.endTradeOffset(), input.dueTradeOffset(),
                dimensions, input.preview(), input.version());
    }

    private static Map<Integer, Long> normalizeDimensions(Map<Integer, Long> values) {
        Map<Integer, Long> dimensions = new TreeMap<>();
        if (values != null) {
            values.forEach((slot, value) -> {
                if (slot == null || slot < 1 || slot > 5 || value != null && value < 1) {
                    throw BadRequestException.localized(INVALID_REQUEST);
                }
                if (value != null) {
                    dimensions.put(slot, value);
                }
            });
        }
        return dimensions;
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[!-~]{1,128}")) {
            throw BadRequestException.localized("error.todo.request-key-required");
        }
    }

    private static boolean validModule(String module) {
        return module != null && module.matches("[a-z][a-z0-9._-]{0,63}");
    }

    private static boolean invalidText(String value, int limit) {
        return value == null || value.isBlank() || value.strip().length() > limit;
    }
}
