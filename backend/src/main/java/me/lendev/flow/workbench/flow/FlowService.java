package me.lendev.flow.workbench.flow;

import static me.lendev.flow.common.auth.PermissionCode.*;
import static me.lendev.flow.workbench.flow.FlowModels.*;

import cn.hutool.crypto.digest.DigestUtil;
import jakarta.validation.Validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.audit.AuditSystemContext;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.exception.BizException;
import me.lendev.flow.common.response.BizResponseCode;
import me.lendev.flow.workbench.todo.TodoAccess;
import me.lendev.flow.workbench.todo.TodoAssigneeException;
import me.lendev.flow.workbench.todo.TodoConflictException;
import me.lendev.flow.workbench.todo.TodoBusinessSource;
import me.lendev.flow.workbench.todo.TodoModels.Source;
import me.lendev.flow.workbench.todo.TodoModels.Status;
import me.lendev.flow.workbench.todo.TodoNotFoundException;
import me.lendev.flow.workbench.todo.TodoSchedule;
import me.lendev.flow.workbench.todo.TodoService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * 校验流程模板及运行权限，按运行、待办顺序加锁，并聚合节点状态与审计。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FlowService {

    private static final String INVALID_SOURCE = "error.todo.source";
    private static final String INVALID_STATE = "error.flow.state";
    private static final String VERSION_CONFLICT = "error.todo.version";
    private final FlowRepository repository;
    private final FlowStarter starter;
    private final TodoService todos;
    private final TodoSchedule schedule;
    private final ObjectMapper json;
    private final Validator validator;
    private final List<TodoBusinessSource> businessSources;

    /**
     * 查询流程模板列表。已发布视角供发起人选择，管理视角包含全部状态。
     */
    List<Definition> definitions(boolean published) {
        TodoAccess.permit(WORKBENCH_TODO);
        TodoAccess.permit(published ? WORKBENCH_TODO_CREATE : WORKBENCH_TODO_FLOW_MANAGE);
        return repository.definitions(published);
    }

    /**
     * 创建模板草稿，同编码的新草稿使用递增的版本号。
     *
     * @throws TodoConflictException 同编码已存在草稿
     * @throws BadRequestException   步骤编号不连续或参数不合法
     */
    @Audited(AuditOperation.TODO_FLOW_DEFINE)
    @Transactional(rollbackFor = Exception.class)
    public FlowAuditHandler.DefinitionChange createDefinition(CreateDefinition request) {
        permitTemplateManage();
        var steps = validSteps(request.steps());
        var latest = repository.latestDefinition(request.code());
        if (latest.filter(item -> item.status() == DefinitionStatus.DRAFT).isPresent()) {
            throw new TodoConflictException("error.flow.definition.draft-exists");
        }
        int revision = latest.map(Definition::revision).orElse(0) + 1;
        var now = schedule.now();
        String name = request.name().strip();
        try {
            long id = repository.createDefinition(request.code(), revision, name, steps, TodoAccess.viewer(), now);
            log.info("流程模板草稿创建，定义 ID={}，编码={}，修订号={}", id, request.code(), revision);
            return new FlowAuditHandler.DefinitionChange(null,
                    new Definition(id, request.code(), revision, name, DefinitionStatus.DRAFT, steps, 0));
        } catch (DuplicateKeyException error) {
            throw new TodoConflictException("error.flow.definition.draft-exists");
        }
    }

    /**
     * 只允许编辑草稿，发布及停用版本保持原内容。内容未变化时幂等返回。
     *
     * @throws TodoConflictException 版本不匹配或模板不是草稿
     */
    @Audited(AuditOperation.TODO_FLOW_DEFINE_EDIT)
    @Transactional(rollbackFor = Exception.class)
    public FlowAuditHandler.DefinitionChange editDefinition(long id, EditDefinition request) {
        permitTemplateManage();
        var before = lockedDefinition(id, request.version(), DefinitionStatus.DRAFT);
        var steps = validSteps(request.steps());
        String name = request.name().strip();
        if (before.name().equals(name) && before.steps().equals(steps)) {
            return new FlowAuditHandler.DefinitionChange(before, before);
        }
        return updateDefinition(before, name, steps, before.status());
    }

    /**
     * 发布后冻结步骤内容。
     *
     * @throws TodoConflictException 版本不匹配或模板不是草稿
     */
    @Audited(AuditOperation.TODO_FLOW_PUBLISH)
    @Transactional(rollbackFor = Exception.class)
    public FlowAuditHandler.DefinitionChange publish(long id, Version request) {
        permitTemplateManage();
        var before = lockedDefinition(id, request.version(), DefinitionStatus.DRAFT);
        return updateDefinition(before, before.name(), before.steps(), DefinitionStatus.PUBLISHED);
    }

    /**
     * 停用该模板版本，已经运行的实例保持原有分工。
     *
     * @throws TodoConflictException 版本不匹配或模板不是已发布状态
     */
    @Audited(AuditOperation.TODO_FLOW_RETIRE)
    @Transactional(rollbackFor = Exception.class)
    public FlowAuditHandler.DefinitionChange retire(long id, Version request) {
        permitTemplateManage();
        var before = lockedDefinition(id, request.version(), DefinitionStatus.PUBLISHED);
        return updateDefinition(before, before.name(), before.steps(), DefinitionStatus.RETIRED);
    }

    private static void permitTemplateManage() {
        TodoAccess.permit(WORKBENCH_TODO);
        TodoAccess.permit(WORKBENCH_TODO_FLOW_MANAGE);
    }

    private Definition lockedDefinition(long id, Long version, DefinitionStatus expected) {
        var found = repository.definition(id, true).orElseThrow(TodoNotFoundException::new);
        checkVersion(found.version(), version);
        if (found.status() != expected) {
            throw new TodoConflictException(INVALID_STATE);
        }
        return found;
    }

    private FlowAuditHandler.DefinitionChange updateDefinition(Definition before, String name, List<Step> steps,
                                                               DefinitionStatus target) {
        var after = new Definition(before.id(), before.code(), before.revision(), name, target, steps, before.version() + 1);
        repository.updateDefinition(after, before.version(), schedule.now());
        log.info("流程模板更新，定义 ID={}，目标状态={}，新版本={}", after.id(), target, after.version());
        return new FlowAuditHandler.DefinitionChange(before, after);
    }

    private static List<Step> validSteps(List<Step> submitted) {
        List<Step> steps = new ArrayList<>();
        for (Step step : submitted) {
            if (step.stepNo() != steps.size() + 1) {
                throw BadRequestException.localized("error.flow.steps");
            }
            steps.add(new Step(step.stepNo(), step.title().strip(), step.assigneeUserId(), step.dueOffsetDays()));
        }
        return List.copyOf(steps);
    }

    /**
     * 工作台发起流程。请求键在发起人范围内幂等：同键同内容返回既有实例，内容不同抛出请求键冲突。
     * 改派他人需改派或流程管理权限。
     */
    Progress start(Start input) {
        TodoAccess.permit(WORKBENCH_TODO);
        TodoAccess.permit(WORKBENCH_TODO_CREATE);
        int actor = TodoAccess.viewer();
        var request = normalize(input);
        String hash = DigestUtil.sha256Hex(json.writeValueAsBytes(request));
        var launch = new Launch(request, actor, null, "USER:" + actor, hash, assignee -> {
            if (assignee != actor) {
                requireAssignPermission();
            }
        });
        Run run = startOnce(launch);
        log.info("工作台流程发起，实例 ID={}，定义 ID={}，发起人 ID={}", run.id(), request.definitionId(), actor);
        return progress(run, actor);
    }

    /**
     * 由已登记的同应用业务模块发起流程。来源及所有负责人均受模块授权范围限制。 重试沿用原请求键；加入外层事务时，发生并发键冲突后应在原事务结束后重试。
     *
     * @param source 已登记的模块及来源业务引用
     * @param input  模板版本、请求键、标题及分工
     * @return 流程标识、状态和版本
     */
    public Started startFromBusiness(Source source, Start input) {
        if (input == null || !validator.validate(input).isEmpty()) {
            throw BadRequestException.localized("error.todo.request");
        }
        if (source == null || source.module() == null || !source.module().matches("[a-z][a-z0-9._-]{0,63}")
                || "workflow".equals(source.module()) || invalidSourceText(source.type(), 64)
                || invalidSourceText(source.id(), 128) || source.ownerId() != null && source.ownerId() < 1) {
            throw BadRequestException.localized(INVALID_SOURCE);
        }
        var reference = new Source(source.module(), source.type().strip(), source.id().strip(), source.ownerId());
        var registration = businessSources.stream().filter(item -> reference.module().equals(item.module())).findFirst()
                .orElseThrow(() -> BadRequestException.localized(INVALID_SOURCE));
        var request = normalize(input);
        String hash = DigestUtil.sha256Hex(json.writeValueAsBytes(List.of(reference, request)));
        var launch = new Launch(request, reference.ownerId(), reference, "MODULE:" + reference.module(), hash, assignee -> {
            if (!registration.mayCreate(reference, assignee)) {
                throw BadRequestException.localized(INVALID_SOURCE);
            }
        });
        try (var actor = AuditSystemContext.open("业务模块 " + reference.module())) {
            var row = startOnce(launch);
            log.info("业务模块流程发起，模块={}，业务 ID={}，实例 ID={}", reference.module(), reference.id(), row.id());
            return new Started(row.id(), row.status(), row.version());
        }
    }

    /**
     * 执行一次发起：先按请求键复用既有实例，再进入独立或聚合审计事务写入。
     * 加入外层事务时并发键冲突直接抛出，由调用方在原事务结束后重试。
     */
    private Run startOnce(Launch launch) {
        var request = launch.request();
        var template = repository.definition(request.definitionId(), false).orElseThrow(TodoNotFoundException::new);
        FlowStarter.assigneeIds(request.assignees(), template).forEach(launch.authorize()::accept);
        var previous = repository.byRequest(launch.scope(), request.requestKey());
        if (previous.isPresent()) {
            return replay(previous.get(), launch.hash());
        }
        boolean joining = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            return (AuditParticipants.active() ? starter.startInAggregate(launch) : starter.start(launch)).after();
        } catch (DuplicateKeyException conflict) {
            if (joining) {
                throw new TodoConflictException("error.todo.request-key");
            }
            return repository.byRequest(launch.scope(), request.requestKey()).map(row -> replay(row, launch.hash()))
                    .orElseThrow(() -> conflict);
        }
    }

    private static boolean invalidSourceText(String value, int length) {
        return value == null || value.isBlank() || value.strip().length() > length;
    }

    private static Start normalize(Start request) {
        if (!request.requestKey().matches("[!-~]{1,128}")) {
            throw BadRequestException.localized("error.todo.request-key-required");
        }
        var assignments = request.assignees() == null ? Map.<Integer, Integer>of() : new TreeMap<>(request.assignees());
        var dimensions = new TreeMap<Integer, Long>();
        if (request.dimensions() != null) {
            request.dimensions().forEach((slot, item) -> {
                if (item != null) {
                    dimensions.put(slot, item);
                }
            });
        }
        return new Start(request.requestKey(), request.definitionId(), request.title().strip(), assignments, dimensions);
    }

    /**
     * 请求键命中既有实例时校验请求哈希：一致则重放原结果，不一致抛出请求键冲突。
     *
     * @throws TodoConflictException 相同请求键携带了不同内容
     */
    static Run replay(RequestMatch saved, String hash) {
        if (!saved.hash().equals(hash)) {
            log.info("流程幂等回放失败，请求键相同但内容不一致，实例 ID={}", saved.run().id());
            throw new TodoConflictException("error.todo.request-key");
        }
        return saved.run();
    }

    /**
     * 查询流程进度详情，按当前查看人裁剪可见信息。
     *
     * @throws TodoNotFoundException 流程不存在或当前人不可见
     */
    Progress detail(long id) {
        int viewer = viewer();
        return progress(repository.run(id, false).orElseThrow(TodoNotFoundException::new), viewer);
    }

    /**
     * 完成当前节点，再创建下一节点待办。下一节点写入失败会一起回滚。
     * 目标待办已完成时直接重放当前进度，不重复推进。
     *
     * @throws TodoConflictException 版本不匹配、流程已结束或节点不是当前办理节点
     * @throws TodoNotFoundException 待办不属于该流程或当前人不可见
     */
    @Audited(AuditOperation.TODO_FLOW_COMPLETE)
    @Transactional(rollbackFor = Exception.class)
    public FlowAuditHandler.RunChange complete(long id, Complete request) {
        int actor = viewer();
        Run before = lockVisible(id, actor);
        Node node = before.steps().stream().filter(step -> Objects.equals(step.todoId(), request.todoId()))
                .findFirst().orElseThrow(TodoNotFoundException::new);
        if (node.assigneeUserId() != actor) {
            throw new BizException(BizResponseCode.ERR_11003);
        }
        if (node.status() == StepStatus.DONE) {
            return FlowAuditHandler.RunChange.replay(before);
        }
        requireRunning(before, request.version());
        if (node.status() != StepStatus.ACTIVE || node.stepNo() != before.currentStepNo()) {
            throw new TodoConflictException(INVALID_STATE);
        }
        var oldTodo = todos.readFlowNode(id, node.activationKey(), request.todoId());
        var closed = todos.closeFlowNode(id, node.activationKey(), request.todoId(), Status.DONE, actor, null);
        List<Node> nodes = new ArrayList<>(before.steps());
        nodes.set(node.stepNo() - 1, node.closed(StepStatus.DONE, null));
        List<FlowAuditHandler.NodeChange> changes = new ArrayList<>();
        changes.add(new FlowAuditHandler.NodeChange(oldTodo, closed));
        var now = schedule.now();
        Run after;
        if (node.stepNo() == nodes.size()) {
            after = before.changed(nodes, node.stepNo(), RunStatus.DONE, now);
        } else {
            var next = starter.activate(before, nodes.get(node.stepNo()));
            nodes.set(node.stepNo(), next.node());
            changes.add(new FlowAuditHandler.NodeChange(null, next.todo()));
            after = before.changed(nodes, node.stepNo() + 1, RunStatus.RUNNING, null);
        }
        repository.writeRun(after, before.version(), now);
        log.info("流程节点完成，实例 ID={}，步骤号={}，待办 ID={}，操作人 ID={}，流程结束={}", id, node.stepNo(),
                request.todoId(), actor, after.status() == RunStatus.DONE);
        return new FlowAuditHandler.RunChange(before, after, List.copyOf(changes));
    }

    /**
     * 原发起人或授权管理人可取消运行中的流程。相同原因重复取消重放当前状态。
     *
     * @throws TodoConflictException 版本不匹配或流程已结束
     */
    @Audited(AuditOperation.TODO_FLOW_CANCEL)
    @Transactional(rollbackFor = Exception.class)
    public FlowAuditHandler.RunChange cancel(long id, Cancel request) {
        int actor = viewer();
        Run before = lockVisible(id, actor);
        requireOwnerOrManager(before, actor);
        String reason = request.reason().strip();
        Node node = before.current();
        if (before.status() == RunStatus.CANCELLED && reason.equals(node.cancelReason())) {
            return FlowAuditHandler.RunChange.replay(before);
        }
        requireRunning(before, request.version());
        var oldTodo = todos.readFlowNode(id, node.activationKey(), node.todoId());
        var cancelled = todos.closeFlowNode(id, node.activationKey(), node.todoId(), Status.CANCELLED, actor, reason);
        var now = schedule.now();
        Run after = withNode(before, node.closed(StepStatus.CANCELLED, reason), RunStatus.CANCELLED, now);
        repository.writeRun(after, before.version(), now);
        log.info("流程取消，实例 ID={}，操作人 ID={}", id, actor);
        return new FlowAuditHandler.RunChange(before, after, List.of(new FlowAuditHandler.NodeChange(oldTodo, cancelled)));
    }

    /**
     * 授权后改派当前或尚未激活的步骤，当前节点的待办同步更新。改派为同一负责人时重放当前状态。
     *
     * @throws TodoConflictException 版本不匹配、流程已结束或步骤已办理完成
     * @throws TodoAssigneeException 新负责人不存在或已停用
     */
    @Audited(AuditOperation.TODO_FLOW_ASSIGN)
    @Transactional(rollbackFor = Exception.class)
    public FlowAuditHandler.RunChange assign(long id, Assign request) {
        int actor = viewer();
        Run before = lockVisible(id, actor);
        requireOwnerOrManager(before, actor);
        requireAssignPermission();
        requireRunning(before, request.version());
        if (request.stepNo() < before.currentStepNo() || request.stepNo() > before.steps().size()) {
            throw new TodoConflictException(INVALID_STATE);
        }
        Node node = before.steps().get(request.stepNo() - 1);
        var person = todos.requireActiveUser(request.assigneeUserId());
        if (node.assigneeUserId() == person.id()) {
            return FlowAuditHandler.RunChange.replay(before);
        }
        List<FlowAuditHandler.NodeChange> changes = List.of();
        if (node.todoId() != null) {
            var oldTodo = todos.readFlowNode(id, node.activationKey(), node.todoId());
            var assigned = todos.assignFlowNode(id, node.activationKey(), node.todoId(), person.id());
            changes = List.of(new FlowAuditHandler.NodeChange(oldTodo, assigned));
        }
        Run after = withNode(before, node.assigned(person.id(), person.name()), before.status(), null);
        repository.writeRun(after, before.version(), schedule.now());
        log.info("流程步骤改派，实例 ID={}，步骤号={}，新负责人 ID={}", id, request.stepNo(), person.id());
        return new FlowAuditHandler.RunChange(before, after, changes);
    }

    Progress currentViewerProgress(Run row) {
        return progress(row, TodoAccess.viewer());
    }

    private static Run withNode(Run before, Node node, RunStatus status, java.time.LocalDateTime closedAt) {
        var nodes = new ArrayList<>(before.steps());
        nodes.set(node.stepNo() - 1, node);
        return before.changed(nodes, before.currentStepNo(), status, closedAt);
    }

    private Run lockVisible(long id, int actor) {
        var found = repository.run(id, true).orElseThrow(TodoNotFoundException::new);
        requireVisible(found, actor);
        return found;
    }

    private static void requireRunning(Run row, Long version) {
        checkVersion(row.version(), version);
        if (row.status() != RunStatus.RUNNING) {
            throw new TodoConflictException(INVALID_STATE);
        }
    }

    private static void checkVersion(long actual, Long expected) {
        if (expected == null || actual != expected) {
            log.info("流程版本冲突，期望版本={}，当前版本={}", expected, actual);
            throw new TodoConflictException(VERSION_CONFLICT);
        }
    }

    private static int viewer() {
        if (!TodoAccess.can(WORKBENCH_TODO)) {
            TodoAccess.permit(WORKBENCH_TODO_CALENDAR);
        }
        return TodoAccess.viewer();
    }

    static void requireAssignPermission() {
        if (!TodoAccess.can(WORKBENCH_TODO_FLOW_MANAGE)) {
            TodoAccess.permit(WORKBENCH_TODO_ASSIGN);
        }
    }

    private static boolean manages(Run row, int viewer) {
        return Objects.equals(row.ownerUserId(), viewer) || TodoAccess.can(WORKBENCH_TODO_FLOW_MANAGE);
    }

    private static void requireOwnerOrManager(Run row, int viewer) {
        if (!manages(row, viewer)) {
            throw new BizException(BizResponseCode.ERR_11003);
        }
    }

    private static void requireVisible(Run row, int viewer) {
        if (!manages(row, viewer) && row.steps().stream()
                .noneMatch(node -> node.todoId() != null && node.assigneeUserId() == viewer)) {
            throw new TodoNotFoundException();
        }
    }

    /**
     * 按查看人裁剪步骤信息，并计算其当前允许执行的动作。
     */
    private static Progress progress(Run row, int viewer) {
        requireVisible(row, viewer);
        boolean manages = manages(row, viewer);
        var steps = row.steps().stream().map(node -> new StepView(node.stepNo(), node.title(), node.assigneeUserId(),
                node.assigneeName(), manages ? node.dueOffsetDays() : null,
                manages || node.assigneeUserId() == viewer ? node.todoId() : null, node.status())).toList();
        List<String> actions = new ArrayList<>();
        if (row.status() == RunStatus.RUNNING) {
            if (row.current().assigneeUserId() == viewer) {
                actions.add("complete");
            }
            if (manages) {
                actions.add("cancel");
                if (TodoAccess.can(WORKBENCH_TODO_ASSIGN) || TodoAccess.can(WORKBENCH_TODO_FLOW_MANAGE)) {
                    actions.add("assign");
                }
            }
        }
        return new Progress(row.id(), row.definitionId(), row.title(), row.ownerUserId(), row.ownerName(),
                row.status(), row.currentStepNo(), row.version(), steps, List.copyOf(actions));
    }
}
