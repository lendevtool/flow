package me.lendev.flow.workbench.flow;

import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO;
import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO_CREATE;
import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO_FLOW_MANAGE;
import static me.lendev.flow.common.exception.BadRequestException.localized;
import static me.lendev.flow.workbench.flow.WorkflowDefinitionModels.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.field.FieldModels;
import me.lendev.flow.pms.notificationgroup.NotificationTransactionConnection;
import me.lendev.flow.field.FieldService;
import me.lendev.flow.workbench.flow.FlowModels.DefinitionStatus;
import me.lendev.flow.workbench.todo.TodoAccess;
import me.lendev.flow.workbench.todo.TodoConflictException;
import me.lendev.flow.workbench.todo.TodoNotFoundException;
import me.lendev.flow.workbench.todo.TodoSchedule;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * 校验和发布角色流程。发布版本冻结节点和字段规则，供实例运行使用。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class WorkflowDefinitionService {

    private final WorkflowDefinitionRepository repository;
    private final WorkflowBusinessRegistry modules;
    private final FieldService fieldService;
    private final TodoSchedule clock;
    private final WorkflowPeople people;
    private final WorkflowDefinitionNotices notices;
    private final NotificationTransactionConnection connection;

    /**
     * 管理视角分页查询模板，latestOnly 为 true 时每个编码仅返回最新修订。
     */
    Page<View> page(boolean latestOnly, String keyword, DefinitionStatus status, int pageNo, int pageSize) {
        permitManage();
        return repository.page(latestOnly, keyword, status, pageNo, pageSize);
    }

    /**
     * 管理视角查询模板详情。
     *
     * @throws TodoNotFoundException 模板不存在
     */
    View detail(long id) {
        permitManage();
        var view = repository.find(id, false).orElseThrow(TodoNotFoundException::new);
        return view.status() == DefinitionStatus.DRAFT ? view.withNotificationWarnings(notices.preview(view.nodes())) : view;
    }

    /**
     * 分页查询同编码的历史修订。
     */
    Page<View> history(long id, int pageNo, int pageSize) {
        permitManage();
        return repository.history(repository.find(id, false).orElseThrow(TodoNotFoundException::new), pageNo, pageSize);
    }

    /**
     * 查询可配置的启用角色。
     */
    List<RoleOption> roles() {
        permitManage();
        return repository.activeRoles();
    }

    /**
     * 查询模板管理人员可选的启用流程附加场景。
     */
    List<NotificationSceneOption> notificationScenes() {
        permitManage();
        return notices.options();
    }

    /**
     * 预校验节点主办配置，返回阻塞清单而不抛异常。
     */
    WorkflowPrimaryHandlerReport validatePrimaryHandlers(List<Node> nodes) {
        permitManage();
        return people.primaryHandlerReport(nodes);
    }

    /**
     * 分页查询可绑定的流程字段。
     */
    Page<FieldModels.Definition> fields(String keyword, int pageNo, int pageSize) {
        permitManage();
        return fieldService.page(new FieldModels.Query(keyword, null, true, pageNo, pageSize));
    }

    /**
     * 按编码批量查询字段定义。
     */
    List<FieldModels.Definition> fieldsByCode(List<String> codes) {
        permitManage();
        return fieldService.findByCodes(codes);
    }

    /**
     * 发起人可发起的已发布模板目录。
     */
    List<View> catalog() {
        permitCatalog();
        return repository.catalog(modules.businessOnlyTemplates());
    }

    /**
     * 分页查询可发起模板目录。
     */
    Page<View> catalog(int pageNo, int pageSize) {
        permitCatalog();
        return repository.catalog(pageNo, pageSize, modules.businessOnlyTemplates());
    }

    private static void permitCatalog() {
        TodoAccess.permit(WORKBENCH_TODO);
        TodoAccess.permit(WORKBENCH_TODO_CREATE);
    }

    /**
     * 创建下一模板版本。同一编码只允许一个待发布草稿。
     *
     * @throws TodoConflictException           同编码已存在草稿
     * @throws WorkflowPrimaryHandlerException 主办配置无法分派
     */
    @Audited(AuditOperation.TODO_FLOW_DEFINE)
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Change create(Write request) {
        prepareWriteConnection();
        permitManage();
        validateStructure(request.nodes(), request.fields(), false);
        people.requirePrimaryHandlers(request.nodes());
        var latest = repository.latest(request.code());
        if (latest.filter(row -> row.getStatus() == DefinitionStatus.DRAFT).isPresent()) {
            throw new TodoConflictException("error.flow.definition.draft-exists");
        }
        int revision = latest.map(row -> row.getRevision() + 1).orElse(1);
        var nodes = notices.merge(request.nodes(), copySource(request.sourceDefinitionId()));
        notices.validate(nodes, false);
        try {
            var created = repository.insert(request.code(), revision, request.name().strip(),
                    bindings(request.fields()), nodes, TodoAccess.viewer(), clock.now());
            log.info("角色流程模板草稿创建，definitionId={}，code={}，revision={}", created.id(), request.code(), revision);
            return new Change(null, created);
        } catch (DuplicateKeyException e) {
            throw new TodoConflictException("error.flow.definition.draft-exists");
        }
    }

    private List<Node> copySource(Long sourceId) {
        if (sourceId == null) {
            return List.of();
        }
        // 已发布和已停用版本的节点关联都已冻结，读取源版本不需要与目标争抢行锁。
        var source = repository.find(sourceId, false).orElseThrow(TodoNotFoundException::new);
        if (source.status() == DefinitionStatus.DRAFT) {
            throw localized("error.workflow.notice.copy-source");
        }
        return source.nodes();
    }

    /**
     * 覆盖已有草稿，保留编码，按版本号防止覆盖他人的修改。
     *
     * @throws TodoConflictException 版本不匹配或模板不是草稿
     */
    @Audited(AuditOperation.TODO_FLOW_DEFINE_EDIT)
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Change edit(long id, Write request) {
        prepareWriteConnection();
        permitManage();
        var before = locked(id, request.version(), DefinitionStatus.DRAFT);
        if (!before.code().equals(request.code())) {
            throw localized("error.workflow.code");
        }
        validateStructure(request.nodes(), request.fields(), false);
        people.requirePrimaryHandlers(request.nodes());
        var nodes = notices.merge(request.nodes(), before.nodes());
        notices.validate(nodes, false);
        var after = repository.update(before, request.name().strip(), bindings(request.fields()),
                nodes, before.status(), clock.now());
        log.info("角色流程模板草稿编辑，definitionId={}，新版本={}", id, after.version());
        return new Change(before, after);
    }

    /**
     * 冻结字段校验规则及字典选项并启用模板版本。
     *
     * @throws TodoConflictException                               版本不匹配或模板不是草稿
     * @throws me.lendev.flow.common.exception.BadRequestException 字段停用或结构不合法
     */
    @Audited(AuditOperation.TODO_FLOW_PUBLISH)
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public Change publish(long id, long version) {
        prepareWriteConnection();
        permitManage();
        var before = locked(id, version, DefinitionStatus.DRAFT);
        var codes = before.fields().stream().map(FieldBinding::code).toList();
        people.requirePrimaryHandlers(before.nodes());
        validateStructure(before.nodes(), codes, true);
        var warnings = notices.validate(before.nodes(), true);
        var after = repository.update(before, before.name(), snapshot(codes),
                before.nodes(), DefinitionStatus.PUBLISHED, clock.now()).withNotificationWarnings(warnings);
        log.info("角色流程模板发布，requestId={} definitionId={} code={} revision={} version={} notificationWarningCount={}",
                AuditParticipants.currentRequestId(), id, before.code(), before.revision(), after.version(), warnings.size());
        return new Change(before, after);
    }

    /**
     * 从可发起目录移除模板，运行实例仍可读取此前的定义。
     *
     * @throws TodoConflictException 版本不匹配或模板不是已发布状态
     */
    @Audited(AuditOperation.TODO_FLOW_RETIRE)
    @Transactional(rollbackFor = Exception.class)
    public Change retire(long id, long version) {
        permitManage();
        var before = locked(id, version, DefinitionStatus.PUBLISHED);
        var after = repository.update(before, before.name(), before.fields(),
                before.nodes(), DefinitionStatus.RETIRED, clock.now());
        log.info("角色流程模板停用，definitionId={}，code={}，revision={}", id, before.code(), before.revision());
        return new Change(before, after);
    }

    private static void permitManage() {
        TodoAccess.permit(WORKBENCH_TODO_FLOW_MANAGE);
    }

    /**
     * 锁定模板并校验版本与状态。草稿变更和发布使用已提交读，避免鉴权查询建立的旧快照用于读取节点引用。
     */
    private View locked(long id, Long version, DefinitionStatus state) {
        var row = repository.find(id, true).orElseThrow(TodoNotFoundException::new);
        if (!Objects.equals(row.version(), version)) {
            log.info("模板版本冲突，definitionId={}，期望版本={}，当前版本={}", id, version, row.version());
            throw new TodoConflictException("error.todo.version");
        }
        if (row.status() != state) {
            throw new TodoConflictException("error.flow.state");
        }
        return row;
    }

    /**
     * 在首条 SQL 前把声明的隔离级别应用到实际连接；外部事务沿用其现有连接。
     */
    private void prepareWriteConnection() {
        if (TransactionAspectSupport.currentTransactionStatus().isNewTransaction()) {
            connection.prepare(true);
        }
    }

    private static List<FieldBinding> bindings(List<String> codes) {
        return codes.stream().map(FieldBinding::new).toList();
    }

    private List<FieldBinding> snapshot(List<String> codes) {
        var configs = fieldService.findByCodes(codes).stream()
                .map(FieldModels.Definition::config).filter(FieldModels.Config::enable)
                .collect(Collectors.toMap(FieldModels.Config::code, Function.identity()));
        var result = new ArrayList<FieldBinding>();
        for (String code : codes) {
            var config = configs.get(code);
            if (config == null) {
                throw localized("error.workflow.field.unavailable", code);
            }
            var options = config.paramGroupId() == null ? List.<FieldModels.Option>of()
                    : fieldService.activeOptions(config.paramGroupId());
            result.add(new FieldBinding(code, config, options));
        }
        return List.copyOf(result);
    }

    private void validateStructure(List<Node> nodes, List<String> fields, boolean publishing) {
        var knownFields = uniqueFieldCodes(fields);
        Map<String, Node> byKey = new HashMap<>();
        Map<String, List<Node>> groups = new HashMap<>();
        for (Node node : nodes) {
            if (byKey.putIfAbsent(node.key(), node) != null) {
                throw localized("error.workflow.node.duplicate", node.key());
            }
            groups.computeIfAbsent(node.parentKey(), ignored -> new ArrayList<>()).add(node);
        }
        if (!groups.containsKey(null)) {
            throw localized("error.workflow.node.root-required");
        }
        boolean hasMultiRole = nodes.stream().anyMatch(node -> node.mode() == Mode.MULTI_ALL);
        Map<String, RoleOption> activeRoles = publishing || hasMultiRole
                ? repository.activeRoles().stream().collect(Collectors.toMap(RoleOption::code, Function.identity()))
                : Map.of();
        for (var entry : groups.entrySet()) {
            if (entry.getKey() != null) {
                var parent = byKey.get(entry.getKey());
                if (parent == null || parent.parentKey() != null) {
                    throw localized("error.workflow.node.parent", entry.getValue().getFirst().title(), entry.getKey());
                }
            }
            validateNodeGroup(entry.getValue(), fields, knownFields, groups.keySet(), activeRoles, publishing);
        }
    }

    private static Set<String> uniqueFieldCodes(List<String> fields) {
        Set<String> knownFields = new HashSet<>();
        for (String code : fields) {
            if (!knownFields.add(code)) {
                throw localized("error.workflow.field.duplicate", code);
            }
        }
        return knownFields;
    }

    private void validateNodeGroup(List<Node> group, List<String> fields, Set<String> knownFields,
                                   Set<String> parentKeys, Map<String, RoleOption> activeRoles, boolean publishing) {
        Set<Integer> positions = new HashSet<>();
        Set<String> editableInGroup = new HashSet<>();
        for (Node node : group) {
            if (node.order() < 1 || node.order() > group.size() || !positions.add(node.order())) {
                throw localized("error.workflow.node.order", node.title(), node.order(), group.size());
            }
            boolean summary = parentKeys.contains(node.key());
            var editable = editable(node, fields, summary);
            var required = list(node.requiredFields());
            validateNode(node, summary, editable, required, knownFields, activeRoles, publishing);
            if (node.mode() == Mode.MULTI_ALL) {
                requireActiveMembers(node, activeRoles);
            }
            if (node.parentKey() != null) {
                for (String field : editable) {
                    if (!editableInGroup.add(field)) {
                        throw localized("error.workflow.parallel-field", field);
                    }
                }
            }
        }
    }

    /**
     * 计算节点可编辑字段：汇总节点不可编辑；首个顶层单办节点缺省可编辑全部字段。
     */
    static List<String> editable(Node node, List<String> fields, boolean summary) {
        if (summary) {
            return List.of();
        }
        if (node.editableFields() == null && node.parentKey() == null && node.order() == 1 && node.mode() != Mode.MULTI_ALL) {
            return fields;
        }
        return list(node.editableFields());
    }

    private static List<String> list(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static void validateNode(Node node, boolean summary, List<String> editable, List<String> required,
                                     Set<String> fields, Map<String, RoleOption> roles, boolean publishing) {
        if (summary) {
            if (node.roleCode() != null || !list(node.roleCodes()).isEmpty() || node.mode() != null || !list(node.editableFields()).isEmpty()
                    || !required.isEmpty() || node.dueOffsetDays() != null) {
                throw localized("error.workflow.node.summary", node.title());
            }
            return;
        }
        validateNodeRoles(node, roles, publishing);
        var editableSet = new HashSet<>(editable);
        if (editableSet.size() != editable.size() || new HashSet<>(required).size() != required.size()) {
            throw localized("error.workflow.node.field.duplicate", node.title());
        }
        for (String code : editable) {
            if (!fields.contains(code)) {
                throw localized("error.workflow.node.field.unknown", node.title(), code);
            }
        }
        if (node.countersign() && !editableSet.isEmpty()) {
            throw localized("error.workflow.node.field.countersign", node.title());
        }
        for (String code : required) {
            if (!editableSet.contains(code)) {
                throw localized("error.workflow.node.field.required", node.title(), code);
            }
        }
    }

    /**
     * 校验节点办理角色。单角色节点在草稿中可暂缺模式和角色，发布时须指定模式和启用角色。
     */
    private static void validateNodeRoles(Node node, Map<String, RoleOption> roles, boolean publishing) {
        var handlingRoles = node.handlingRoles();
        if (node.mode() == Mode.MULTI_ALL) {
            if (node.roleCode() != null || handlingRoles.size() < 2 || new HashSet<>(handlingRoles).size() != handlingRoles.size()) {
                throw localized("error.workflow.multi-role", node.title());
            }
        } else if (!list(node.roleCodes()).isEmpty()
                || publishing && (node.mode() == null || handlingRoles.isEmpty() || !roles.keySet().containsAll(handlingRoles))) {
            throw localized("error.workflow.role", node.title());
        }
    }

    private void requireActiveMembers(Node node, Map<String, RoleOption> roles) {
        var missing = node.handlingRoles().stream().filter(code -> {
            var role = roles.get(code);
            return role == null || !role.hasEnabledMembers();
        }).toList();
        if (!missing.isEmpty()) {
            var names = people.roleNames(missing);
            throw localized("error.workflow.multi-role-unassigned",
                    missing.stream().map(code -> names.getOrDefault(code, code)).collect(Collectors.joining("、")));
        }
    }
}
