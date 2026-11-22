package me.lendev.flow.workbench.todo;

import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO_ASSIGN;
import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO_MANAGE;
import static me.lendev.flow.workbench.todo.TodoModels.*;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.common.exception.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 待办独立写入口，审计事实随业务事务提交。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class TodoMutations {

    private static final String SCHEDULE_MODE = "scheduleMode";
    private static final Set<String> EDITABLE = Set.of("title", "description", "assigneeUserId", "priority",
            SCHEDULE_MODE, "startDate", "endDate", "dueDate", "marketCode", "tradeBaseDate", "startTradeOffset",
            "endTradeOffset", "dueTradeOffset", "dimensions", "preview", "version");

    private final TodoRepository repository;
    private final TodoPublicDimensions publicDimensions;
    private final TodoPersonalDimensions personalDimensions;
    private final TodoSchedule schedule;
    private final ObjectMapper json;
    private final TodoAuditHandler audit;

    /**
     * 写操作结果。changed 为 false 表示幂等命中或内容未变化，未产生写入。
     */
    record Change(Todo before, Todo after, boolean changed) {

    }

    /**
     * 独立事务创建待办并记录审计，请求键命中时幂等返回。
     */
    @Audited(AuditOperation.TODO_CREATE)
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public Change create(Write request, TodoRepository.Creation creation) {
        return createNew(request, creation);
    }

    /**
     * 在调用方聚合审计事务内创建待办，审计事实并入外层事务。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Change createInAggregate(Write request, TodoRepository.Creation creation) {
        Change change = createNew(request, creation);
        if (change.changed()) {
            AuditParticipants.addChanges(java.util.List.of(audit.todoChange(null, change.after())));
        }
        return change;
    }

    private Change createNew(Write request, TodoRepository.Creation creation) {
        var existing = repository.byRequest(creation.scope(), request.requestKey());
        if (existing.isPresent()) {
            return new Change(null, TodoService.replay(existing.get(), creation.hash()), false);
        }
        if (creation.origin() == Origin.MANUAL) {
            requirePreview(request);
        }
        Todo created = insert(request, creation, schedule.resolve(request), false);
        log.info("待办创建成功，待办 ID={}，来源={}，负责人 ID={}", created.id(), creation.origin(), request.assigneeUserId());
        if (!creation.personalDimensions().isEmpty()) {
            var tags = personalDimensions.tagOnCreate(created.id(), creation.ownerId(), creation.personalDimensions());
            AuditParticipants.addChanges(java.util.List.of(audit.personalTagChange(tags, "TODO_PERSONAL_TAG")));
        }
        return new Change(null, created, true);
    }

    /**
     * 校验负责人与公共维度后写入待办，inherited 为 true 时放宽维度必填校验（周期/流程继承）。
     */
    Todo insert(Write request, TodoRepository.Creation creation, TodoSchedule.Resolved dates, boolean inherited) {
        Person assignee = activeUser(request.assigneeUserId());
        publicDimensions.validate(request.dimensions(), Map.of(), inherited);
        Integer viewer = TodoAccess.viewerOrNull();
        Long departmentId = viewer == null ? null : repository.primaryDepartmentId(viewer);
        return get(repository.insert(request, assignee, dates, creation, departmentId, schedule.now()));
    }

    /**
     * 部分更新待办：锁定后校验操作权限与版本号，内容与排期均未变化时幂等返回。
     * 切换排期模式会清空原排期字段，交易排期必须携带预览确认。
     *
     * @param patch 仅允许 EDITABLE 中的字段，必须携带 version
     * @throws TodoConflictException 版本不匹配或待办已关闭
     * @throws TodoNotFoundException 待办不存在或当前人无编辑权限
     */
    @Audited(AuditOperation.TODO_EDIT)
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public Change edit(long id, ObjectNode patch, int viewer) {
        Todo before = lockVisible(id, viewer);
        requireAction(before, viewer, "edit");
        Write old = before.toWrite();
        Write request = TodoService.normalize(merge(old, patch), before.assigneeUserId());
        requireVersion(before, request.version());
        boolean reassigned = request.assigneeUserId() != before.assigneeUserId();
        if (reassigned) {
            TodoAccess.permit(WORKBENCH_TODO_ASSIGN);
        }
        boolean sameSchedule = sameSchedule(request, old);
        if (!sameSchedule) {
            requirePreview(request);
        }
        boolean retainedSchedule = sameSchedule && request.preview() == null;
        TodoSchedule.Resolved dates = retainedSchedule
                ? existingSchedule(before)
                : schedule.resolve(request);
        if (TodoService.content(request).equals(TodoService.content(old))
                && dates.dates().equals(new Dates(before.startDate(), before.endDate(), before.dueDate()))) {
            return new Change(before, before, false);
        }
        Person assignee = reassigned ? activeUser(request.assigneeUserId())
                : new Person(before.assigneeUserId(), before.assigneeName());
        publicDimensions.validate(request.dimensions(), before.dimensions(), false);
        repository.edit(before, request, assignee, dates, schedule.now());
        log.info("待办编辑成功，待办 ID={}，负责人变更={}", id, reassigned);
        return new Change(before, get(id), true);
    }

    private TodoSchedule.Resolved existingSchedule(Todo before) {
        Long marketId = before.marketCode() == null ? null : repository.marketId(before.marketCode());
        return new TodoSchedule.Resolved(new Dates(before.startDate(), before.endDate(), before.dueDate()), marketId);
    }

    /**
     * 开始处理待办，OPEN 转为 IN_PROGRESS。
     */
    @Audited(AuditOperation.TODO_START)
    @Transactional(rollbackFor = Exception.class)
    public Change start(long id, Action request, int viewer) {
        return transition(id, request, viewer, "start", Status.IN_PROGRESS, null, null);
    }

    /**
     * 完成待办，转为 DONE。
     */
    @Audited(AuditOperation.TODO_COMPLETE)
    @Transactional(rollbackFor = Exception.class)
    public Change complete(long id, Action request, int viewer) {
        return transition(id, request, viewer, "complete", Status.DONE, null, null);
    }

    /**
     * 取消待办，转为 CANCELLED，必须填写取消原因。
     */
    @Audited(AuditOperation.TODO_CANCEL)
    @Transactional(rollbackFor = Exception.class)
    public Change cancel(long id, Action request, int viewer) {
        return transition(id, request, viewer, "cancel", Status.CANCELLED, "MANUAL", reason(request.reason()));
    }

    /**
     * 发起人改派待办负责人，需待办改派权限。
     */
    @Audited(AuditOperation.TODO_ASSIGN)
    @Transactional(rollbackFor = Exception.class)
    public Change assign(long id, Action request, int viewer) {
        Todo before = lockVisible(id, viewer);
        requireAction(before, viewer, "assign");
        return assign(before, request);
    }

    /**
     * 管理身份改派任意待办，需待办管理权限；已关闭或流程来源的待办不可改派。
     */
    @Audited(AuditOperation.TODO_ASSIGN)
    @Transactional(rollbackFor = Exception.class)
    public Change manageAssign(long id, Action request) {
        TodoAccess.permit(WORKBENCH_TODO_MANAGE);
        Todo before = lock(id);
        if (before.status().closed() || before.origin() == Origin.FLOW) {
            throw new TodoConflictException("error.todo.status");
        }
        return assign(before, request);
    }

    private Change assign(Todo before, Action request) {
        requireVersion(before, request.version());
        Person person = activeUser(request.assigneeUserId());
        if (before.assigneeUserId() == person.id()) {
            return new Change(before, before, false);
        }
        repository.assign(before, person, schedule.now());
        log.info("待办负责人改派，待办 ID={}，原负责人 ID={}，新负责人 ID={}", before.id(), before.assigneeUserId(), person.id());
        return new Change(before, get(before.id()), true);
    }

    /**
     * 校验负责人为有效账号；写事务内会锁定用户行，防止并发改派到停用账号。
     *
     * @throws TodoAssigneeException ID 为空、不存在或账号已停用
     */
    Person activeUser(Integer id) {
        if (id == null || id < 1) {
            throw new TodoAssigneeException();
        }
        boolean lock = TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        return repository.person(id, true, lock).orElseThrow(TodoAssigneeException::new);
    }

    /**
     * 读取待办，不存在时抛出 {@link TodoNotFoundException}。
     */
    Todo get(long id) {
        return repository.find(id).orElseThrow(TodoNotFoundException::new);
    }

    /**
     * 校验并规范化取消原因，必填且不超过 500 字符。
     */
    static String reason(String input) {
        if (input == null || input.isBlank() || input.strip().length() > 500) {
            throw BadRequestException.localized("error.todo.cancel-reason");
        }
        return input.strip();
    }

    private Change transition(long id, Action request, int viewer, String action, Status target,
                              String kind, String reason) {
        Todo before = lockVisible(id, viewer);
        requireAction(before, viewer, action);
        requireVersion(before, request.version());
        repository.transition(before, target, viewer, kind, reason, schedule.now());
        log.info("待办状态流转，待办 ID={}，动作={}，目标状态={}，操作人 ID={}", id, action, target, viewer);
        return new Change(before, get(id), true);
    }

    private Todo lockVisible(long id, int viewer) {
        var reference = get(id);
        TodoService.requireVisible(reference, viewer);
        var locked = lock(reference);
        TodoService.requireVisible(locked, viewer);
        return locked;
    }

    private Todo lock(long id) {
        return lock(get(id));
    }

    /**
     * 周期实例先锁规则行再锁待办行，与规则变更保持相同加锁顺序以避免死锁。
     */
    private Todo lock(Todo reference) {
        if (reference.seriesId() != null) {
            repository.lockSeries(reference.seriesId());
        }
        return repository.lock(reference.id()).orElseThrow(TodoNotFoundException::new);
    }

    private static void requireAction(Todo before, int viewer, String action) {
        if (before.status().closed()) {
            throw new TodoConflictException("error.todo.status");
        }
        if (before.origin() == Origin.FLOW || !TodoService.allowed(before, viewer).contains(action)) {
            throw new TodoNotFoundException();
        }
    }

    private static void requireVersion(Todo before, Long expected) {
        if (expected == null || expected != before.version()) {
            log.info("待办版本冲突，待办 ID={}，期望版本={}，当前版本={}", before.id(), expected, before.version());
            throw new TodoConflictException("error.todo.version");
        }
    }

    private Write merge(Write previous, ObjectNode patch) {
        ObjectNode merged = json.valueToTree(previous);
        String mode = patch.hasNonNull(SCHEDULE_MODE) ? patch.path(SCHEDULE_MODE).asString() : previous.scheduleMode();
        if (!mode.equals(previous.scheduleMode())) {
            merged.remove(java.util.List.of("startDate", "endDate", "dueDate", "marketCode", "tradeBaseDate",
                    "startTradeOffset", "endTradeOffset", "dueTradeOffset"));
        }
        for (Iterator<Map.Entry<String, JsonNode>> entries = patch.properties().iterator(); entries.hasNext(); ) {
            var entry = entries.next();
            if (!EDITABLE.contains(entry.getKey())) {
                throw BadRequestException.localized("error.todo.request");
            }
            merged.set(entry.getKey(), entry.getValue());
        }
        if (!patch.hasNonNull("version")) {
            throw new TodoConflictException("error.todo.version");
        }
        try {
            return json.treeToValue(merged, Write.class);
        } catch (tools.jackson.core.JacksonException invalid) {
            throw BadRequestException.localized("error.todo.request");
        }
    }

    private static boolean sameSchedule(Write left, Write right) {
        return Objects.equals(left.scheduleMode(), right.scheduleMode())
                && Objects.equals(left.startDate(), right.startDate()) && Objects.equals(left.endDate(), right.endDate())
                && Objects.equals(left.dueDate(), right.dueDate()) && Objects.equals(left.marketCode(), right.marketCode())
                && Objects.equals(left.tradeBaseDate(), right.tradeBaseDate())
                && Objects.equals(left.startTradeOffset(), right.startTradeOffset())
                && Objects.equals(left.endTradeOffset(), right.endTradeOffset())
                && Objects.equals(left.dueTradeOffset(), right.dueTradeOffset());
    }

    private static void requirePreview(Write request) {
        if (TodoSchedule.mode(request) == ScheduleMode.TRADING && request.preview() == null) {
            throw BadRequestException.localized("error.todo.schedule-preview-required");
        }
    }
}
