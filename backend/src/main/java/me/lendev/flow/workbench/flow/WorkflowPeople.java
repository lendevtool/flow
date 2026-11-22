package me.lendev.flow.workbench.flow;

import static me.lendev.flow.common.exception.BadRequestException.localized;
import static me.lendev.flow.pms.domain.entity.table.RoleTableDef.ROLE;
import static me.lendev.flow.pms.domain.entity.table.UserRoleTableDef.USER_ROLE;
import static me.lendev.flow.pms.domain.entity.table.UserTableDef.USER;
import static me.lendev.flow.workbench.flow.WorkflowRuntimeModels.*;
import static me.lendev.flow.workbench.flow.entity.table.WorkflowDelegationTableDef.WORKFLOW_DELEGATION;
import static me.lendev.flow.workbench.flow.entity.table.WorkflowHandlerTableDef.WORKFLOW_HANDLER;

import com.mybatisflex.core.query.QueryWrapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.pms.domain.entity.User;
import me.lendev.flow.pms.domain.entity.Role;
import me.lendev.flow.pms.mapper.RoleMapper;
import me.lendev.flow.pms.mapper.UserMapper;
import me.lendev.flow.workbench.flow.WorkflowDefinitionModels.Mode;
import me.lendev.flow.workbench.flow.WorkflowDefinitionModels.Node;
import me.lendev.flow.workbench.flow.WorkflowPrimaryHandlerReport.Issue;
import me.lendev.flow.workbench.flow.WorkflowPrimaryHandlerReport.Reason;
import me.lendev.flow.workbench.flow.entity.WorkflowDelegation;
import me.lendev.flow.workbench.flow.entity.WorkflowHandler;
import me.lendev.flow.workbench.flow.mapper.WorkflowDelegationMapper;
import me.lendev.flow.workbench.flow.mapper.WorkflowHandlerMapper;
import me.lendev.flow.workbench.todo.TodoNotFoundException;
import me.lendev.flow.workbench.todo.TodoSchedule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.dev33.satoken.stp.StpUtil;

/**
 * 角色成员、主备与休假转办管理。转办只参与对应待办的人员计算。
 */
@Service
@RequiredArgsConstructor
@Slf4j
class WorkflowPeople {

    private static final String PRIMARY_HANDLER = "PRIMARY";
    private static final String BACKUP_HANDLER = "BACKUP";

    private final RoleMapper roles;
    private final UserMapper users;
    private final WorkflowHandlerMapper handlers;
    private final WorkflowDelegationMapper delegations;
    private final WorkflowDefinitionRepository definitions;
    private final TodoSchedule clock;

    /**
     * 查询角色成员与主备配置，仅超级管理员可操作。
     *
     * @throws TodoNotFoundException 角色不存在
     */
    Handlers handlers(int roleId) {
        StpUtil.checkRole(RoleType.SUPER_ADMIN);
        if (roles.selectOneById(roleId) == null) {
            throw new TodoNotFoundException();
        }
        return handlersFor(roleId);
    }

    /**
     * 查询穿梭分配列表所需的主备配置及主办要求。
     */
    @Transactional(readOnly = true)
    public HandlerSummary handlerSummary(int roleId) {
        StpUtil.checkRole(RoleType.SUPER_ADMIN);
        var role = roles.selectOneById(roleId);
        if (role == null) {
            throw new TodoNotFoundException();
        }
        var rows = configurations(roleId);
        var primary = primaryId(rows);
        return new HandlerSummary(primary, backupIds(rows), primary != null && enabled(primary),
                definitions.requiresPrimaryHandler(role.getCode()));
    }

    /**
     * 角色行锁下替换主备，关联表外键同时保证成员关系。
     * 主办与备办不可重复，且必须是角色的启用成员。
     *
     * @throws TodoNotFoundException                               角色不存在
     * @throws me.lendev.flow.common.exception.BadRequestException 人员重复或非启用成员
     */
    @Transactional(rollbackFor = Exception.class)
    public Handlers update(int roleId, HandlerWrite input) {
        StpUtil.checkRole(RoleType.SUPER_ADMIN);
        if (roles.selectOneByQuery(QueryWrapper.create().where(ROLE.ID.eq(roleId)).forUpdate()) == null) {
            throw new TodoNotFoundException();
        }
        var enabled = members(roleId).stream().filter(Member::enabled).map(Member::id).toList();
        var selected = new HashSet<>(input.backupUserIds());
        if (selected.size() != input.backupUserIds().size()
                || input.primaryUserId() != null && !selected.add(input.primaryUserId())
                || !enabled.containsAll(selected)) {
            throw localized("error.workflow.handlers");
        }
        handlers.deleteByQuery(QueryWrapper.create().where(WORKFLOW_HANDLER.ROLE_ID.eq(roleId)));
        if (input.primaryUserId() != null) {
            saveHandler(roleId, input.primaryUserId(), true);
        }
        input.backupUserIds().forEach(id -> saveHandler(roleId, id, false));
        log.info("角色办理人配置更新，roleId={}，primaryUserId={}，备办数={}", roleId, input.primaryUserId(),
                input.backupUserIds().size());
        return handlersFor(roleId);
    }

    private Handlers handlersFor(int roleId) {
        var rows = configurations(roleId);
        return new Handlers(members(roleId), primaryId(rows), backupIds(rows));
    }

    private static Integer primaryId(List<WorkflowHandler> rows) {
        return rows.stream().filter(row -> PRIMARY_HANDLER.equals(row.getKind()))
                .map(WorkflowHandler::getUserId).findFirst().orElse(null);
    }

    private static List<Integer> backupIds(List<WorkflowHandler> rows) {
        return rows.stream().filter(row -> BACKUP_HANDLER.equals(row.getKind())).map(WorkflowHandler::getUserId).toList();
    }

    private List<WorkflowHandler> configurations(int roleId) {
        return handlers.selectListByQuery(QueryWrapper.create().where(WORKFLOW_HANDLER.ROLE_ID.eq(roleId))
                .orderBy(WORKFLOW_HANDLER.USER_ID.asc()));
    }

    private void saveHandler(int roleId, int userId, boolean primary) {
        var row = new WorkflowHandler();
        row.setRoleId(roleId);
        row.setUserId(userId);
        row.setKind(primary ? PRIMARY_HANDLER : BACKUP_HANDLER);
        row.setPrimaryRoleId(primary ? roleId : null);
        handlers.insertSelective(row);
    }

    private List<Member> members(int roleId) {
        return users.selectListByQuery(QueryWrapper.create().select(USER.ID, USER.USERNAME, USER.NAME_CN, USER.ENABLE)
                        .from(USER).where(USER.ID.in(QueryWrapper.create().select(USER_ROLE.USER_ID).from(USER_ROLE)
                                .where(USER_ROLE.ROLE_ID.eq(roleId)))).orderBy(USER.ID.asc()))
                .stream().map(WorkflowPeople::member).toList();
    }

    /**
     * 查询委托候选人：启用且非本人，本人主办角色的备办优先，最多 50 条。
     */
    List<Member> candidates(int viewer, String keyword) {
        var query = QueryWrapper.create().select(USER.ID, USER.USERNAME, USER.NAME_CN, USER.ENABLE).from(USER)
                .where(USER.ENABLE.eq(true)).and(USER.ID.ne(viewer));
        if (keyword != null && !keyword.isBlank()) {
            query.and(USER.USERNAME.like(keyword.strip()).or(USER.NAME_CN.like(keyword.strip())));
        }
        var backup = QueryWrapper.create().select(WORKFLOW_HANDLER.USER_ID).from(WORKFLOW_HANDLER)
                .where(WORKFLOW_HANDLER.KIND.eq(BACKUP_HANDLER)).and(WORKFLOW_HANDLER.ROLE_ID.in(QueryWrapper.create()
                        .select(WORKFLOW_HANDLER.ROLE_ID).from(WORKFLOW_HANDLER)
                        .where(WORKFLOW_HANDLER.USER_ID.eq(viewer)).and(WORKFLOW_HANDLER.KIND.eq(PRIMARY_HANDLER))));
        var preferred = new HashSet<>(handlers.selectListByQuery(backup).stream().map(WorkflowHandler::getUserId).toList());
        var order = com.mybatisflex.core.query.QueryMethods.case_().when(USER.ID.in(preferred, !preferred.isEmpty()))
                .then(0).else_(1).end();
        if (!preferred.isEmpty()) {
            query.orderBy(order.asc());
        }
        return users.selectListByQuery(query.orderBy(USER.ID.asc()).limit(50)).stream().map(WorkflowPeople::member).toList();
    }

    private static Member member(User user) {
        return new Member(user.getId().intValue(), user.getNameCn() == null || user.getNameCn().isBlank()
                ? user.getUsername() : user.getNameCn(), Boolean.TRUE.equals(user.getEnable()));
    }

    /**
     * 查询用户展示名，ID 为空或用户不存在时返回 null。
     */
    String name(Integer id) {
        if (id == null) {
            return null;
        }
        var user = users.selectOneByQuery(QueryWrapper.create().select(USER.ID, USER.USERNAME, USER.NAME_CN, USER.ENABLE)
                .from(USER).where(USER.ID.eq(id)));
        return user == null ? null : member(user).name();
    }

    /**
     * 批量查询用户展示名。
     */
    Map<Integer, String> names(Collection<Integer> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return users.selectListByQuery(QueryWrapper.create().select(USER.ID, USER.USERNAME, USER.NAME_CN)
                        .where(USER.ID.in(ids))).stream().map(WorkflowPeople::member)
                .collect(Collectors.toMap(Member::id, Member::name));
    }

    /**
     * 批量查询角色编码对应的名称。
     */
    Map<String, String> roleNames(List<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        return roles.selectListByQuery(QueryWrapper.create().select(ROLE.CODE, ROLE.NAME).where(ROLE.CODE.in(codes)))
                .stream().collect(Collectors.toMap(Role::getCode, Role::getName));
    }

    /**
     * 判断用户是否为启用账号。
     */
    boolean enabled(int id) {
        return users.selectCountByQuery(QueryWrapper.create().where(USER.ID.eq(id)).and(USER.ENABLE.eq(true))) > 0;
    }

    /**
     * 事务内固定的主办快照：角色与主办用户。
     */
    record PrimaryHandler(int roleId, String roleName, int userId) {
    }

    private record PrimaryHandlers(WorkflowPrimaryHandlerReport report, Map<String, PrimaryHandler> byRoleCode) {
    }

    /**
     * 校验模板主办配置并返回阻塞节点清单，不抛异常。
     */
    WorkflowPrimaryHandlerReport primaryHandlerReport(List<Node> nodes) {
        return checkPrimaryHandlers(nodes).report();
    }

    /**
     * 校验全部单人节点，并返回本次事务分派时使用的主办快照。
     *
     * @throws WorkflowPrimaryHandlerException 存在无法分派的主办节点
     */
    Map<String, PrimaryHandler> requirePrimaryHandlers(List<Node> nodes) {
        var checked = checkPrimaryHandlers(nodes);
        if (!checked.report().valid()) {
            log.info("主办配置校验失败，阻塞节点数={}", checked.report().missingPrimaryHandlers().size());
            throw new WorkflowPrimaryHandlerException(checked.report());
        }
        return checked.byRoleCode();
    }

    private PrimaryHandlers checkPrimaryHandlers(List<Node> nodes) {
        var primary = nodes.stream().filter(node -> node.mode() == Mode.PRIMARY).toList();
        var codes = primary.stream().map(Node::roleCode).filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toSet());
        Map<String, Role> byCode = codes.isEmpty() ? Map.of()
                : roles.selectListByQuery(QueryWrapper.create().where(ROLE.CODE.in(codes))).stream()
                .collect(Collectors.toMap(Role::getCode, role -> role));
        var enabledRoles = byCode.values().stream().filter(role -> Boolean.TRUE.equals(role.getEnable()))
                .map(role -> role.getId().intValue()).toList();
        var assigned = enabledPrimaryUsers(enabledRoles);
        var valid = new HashMap<String, PrimaryHandler>();
        var issues = new ArrayList<Issue>();
        for (var node : primary) {
            String code = node.roleCode();
            boolean hasRole = code != null && !code.isBlank();
            var role = hasRole ? byCode.get(code) : null;
            Reason reason;
            if (!hasRole) {
                reason = Reason.ROLE_REQUIRED;
            } else if (role == null) {
                reason = Reason.ROLE_NOT_FOUND;
            } else if (!Boolean.TRUE.equals(role.getEnable())) {
                reason = Reason.ROLE_DISABLED;
            } else {
                int roleId = role.getId().intValue();
                Integer userId = assigned.get(roleId);
                if (userId != null) {
                    valid.put(code, new PrimaryHandler(roleId, role.getName(), userId));
                    continue;
                }
                reason = Reason.PRIMARY_UNAVAILABLE;
            }
            issues.add(primaryHandlerIssue(node, role, reason));
        }
        return new PrimaryHandlers(new WorkflowPrimaryHandlerReport(issues), Map.copyOf(valid));
    }

    private static Issue primaryHandlerIssue(Node node, Role role, Reason reason) {
        return new Issue(node.key(), node.title(), reason == Reason.ROLE_REQUIRED ? null : node.roleCode(),
                role == null ? null : role.getName(), role == null ? null : role.getNameEn(), reason);
    }

    private Map<Integer, Integer> enabledPrimaryUsers(Collection<Integer> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        // wf_role_handler 的外键保证配置人员仍是角色成员。
        var query = QueryWrapper.create().select(WORKFLOW_HANDLER.ROLE_ID, WORKFLOW_HANDLER.USER_ID)
                .from(WORKFLOW_HANDLER).join(USER).on(WORKFLOW_HANDLER.USER_ID.eq(USER.ID))
                .where(WORKFLOW_HANDLER.ROLE_ID.in(roleIds)).and(WORKFLOW_HANDLER.KIND.eq(PRIMARY_HANDLER))
                .and(USER.ENABLE.eq(true));
        return handlers.selectListByQuery(query).stream()
                .collect(Collectors.toMap(WorkflowHandler::getRoleId, WorkflowHandler::getUserId));
    }

    /**
     * 按已校验的主办快照分派，主办生效委托时同时双发受托人。
     */
    Dispatch primaryDispatch(PrimaryHandler handler, LocalDateTime now) {
        return new Dispatch(handler.roleId(), handler.roleName(), primaryReceivers(handler.userId(), now));
    }

    /**
     * 单角色节点分派：PRIMARY 取主办，BROADCAST/ALL 取全部启用成员。
     * 角色不存在或停用时返回空接收人列表。
     */
    Dispatch singleRoleDispatch(Node node, LocalDateTime now) {
        var role = roles.selectOneByQuery(QueryWrapper.create().where(ROLE.CODE.eq(node.roleCode(), true)).and(ROLE.ENABLE.eq(true)));
        if (role == null) {
            return new Dispatch(null, node.roleCode(), List.of());
        }
        int roleId = role.getId().intValue();
        List<Receiver> target = switch (node.mode()) {
            case PRIMARY -> {
                Integer primary = enabledPrimaryUsers(List.of(roleId)).get(roleId);
                yield primary == null ? List.of() : primaryReceivers(primary, now);
            }
            case BROADCAST, ALL -> {
                var identity = node.mode() == Mode.BROADCAST ? Identity.BROADCAST : Identity.COUNTERSIGN;
                yield members(roleId).stream().filter(Member::enabled)
                        .map(m -> new Receiver(m.id(), identity, null)).toList();
            }
            case MULTI_ALL -> throw new IllegalArgumentException("多角色会签需要按角色分派");
        };
        return new Dispatch(roleId, role.getName(), target);
    }

    /**
     * 每个指定角色至少有一名可处理成员，跨角色成员只派发一次。
     */
    Dispatch multiRoleDispatch(List<String> codes, IntPredicate allowed) {
        var byCode = roles.selectListByQuery(QueryWrapper.create().select(ROLE.ID, ROLE.CODE, ROLE.NAME, ROLE.ENABLE)
                .where(ROLE.CODE.in(codes))).stream().collect(Collectors.toMap(Role::getCode, role -> role));
        var eligible = new TreeSet<Integer>();
        var permissions = new HashMap<Integer, Boolean>();
        var missing = new ArrayList<String>();
        for (String code : codes) {
            var role = byCode.get(code);
            var available = role != null && Boolean.TRUE.equals(role.getEnable())
                    ? members(role.getId().intValue()).stream().filter(Member::enabled).map(Member::id)
                    .filter(id -> permissions.computeIfAbsent(id, allowed::test)).toList()
                    : List.<Integer>of();
            if (available.isEmpty()) {
                missing.add(role == null ? code : role.getName());
            }
            eligible.addAll(available);
        }
        if (!missing.isEmpty()) {
            return new Dispatch(null, null, List.of(), missing);
        }
        return new Dispatch(null, null, eligible.stream().map(id -> new Receiver(id, Identity.COUNTERSIGN, null)).toList());
    }

    /**
     * 主办接收人列表：本人加生效委托的启用受托人。
     */
    List<Receiver> primaryReceivers(int id, LocalDateTime now) {
        List<Receiver> target = new ArrayList<>();
        target.add(new Receiver(id, Identity.PRIMARY, null));
        active(id, now).filter(row -> enabled(row.getDelegateUserId()))
                .ifPresent(row -> target.add(new Receiver(row.getDelegateUserId(), Identity.DELEGATE, row.getId())));
        return List.copyOf(target);
    }

    /**
     * 查询用户在指定时间生效且未取消的委托。
     */
    Optional<WorkflowDelegation> active(int owner, LocalDateTime now) {
        return Optional.ofNullable(delegations.selectOneByQuery(QueryWrapper.create()
                .where(WORKFLOW_DELEGATION.OWNER_USER_ID.eq(owner)).and(WORKFLOW_DELEGATION.CANCELLED_AT.isNull())
                .and(WORKFLOW_DELEGATION.STARTS_AT.le(now)).and(WORKFLOW_DELEGATION.ENDS_AT.gt(now))));
    }

    /**
     * 查询用户的委托历史，按开始时间倒序。
     */
    List<Delegation> list(int owner) {
        return delegations.selectListByQuery(QueryWrapper.create().where(WORKFLOW_DELEGATION.OWNER_USER_ID.eq(owner))
                        .orderBy(WORKFLOW_DELEGATION.STARTS_AT.desc(), WORKFLOW_DELEGATION.ID.desc())).stream()
                .map(this::view).toList();
    }

    WorkflowDelegation owned(long id, int viewer) {
        return owned(id, viewer, false);
    }

    /**
     * 读取本人委托，lock 为 true 时加行锁。
     *
     * @throws TodoNotFoundException 委托不存在或非本人所有
     */
    WorkflowDelegation owned(long id, int viewer, boolean lock) {
        var query = QueryWrapper.create().where(WORKFLOW_DELEGATION.ID.eq(id));
        var row = delegations.selectOneByQuery(lock ? query.forUpdate() : query);
        if (row == null || !Objects.equals(row.getOwnerUserId(), viewer)) {
            throw new TodoNotFoundException();
        }
        return row;
    }

    /**
     * 推导委托展示状态：CANCELLED/ENDED/SCHEDULED/ACTIVE。
     */
    Delegation view(WorkflowDelegation row) {
        var now = now();
        String state;
        if (row.getCancelledAt() != null) {
            state = "CANCELLED";
        } else if (!now.isBefore(row.getEndsAt())) {
            state = "ENDED";
        } else {
            state = now.isBefore(row.getStartsAt()) ? "SCHEDULED" : "ACTIVE";
        }
        return new Delegation(row.getId(), row.getDelegateUserId(), name(row.getDelegateUserId()), row.getStartsAt(),
                row.getEndsAt(), row.getCancelledAt(), state);
    }

    /**
     * 使用本人账号行锁校验时段重叠，同一时间只能存在一个有效转办人。
     *
     * @throws me.lendev.flow.common.exception.BadRequestException 受托人无效、区间不合法或与既有委托重叠
     */
    @Transactional(rollbackFor = Exception.class)
    public Delegation create(int owner, DelegationWrite input) {
        lockUser(owner);
        if (owner == input.delegateUserId() || !enabled(input.delegateUserId())
                || !input.startsAt().isBefore(input.endsAt()) || !input.endsAt().isAfter(now())
                || delegations.selectCountByQuery(QueryWrapper.create().where(WORKFLOW_DELEGATION.OWNER_USER_ID.eq(owner))
                .and(WORKFLOW_DELEGATION.CANCELLED_AT.isNull()).and(WORKFLOW_DELEGATION.STARTS_AT.lt(input.endsAt()))
                .and(WORKFLOW_DELEGATION.ENDS_AT.gt(input.startsAt()))) > 0) {
            throw localized("error.workflow.delegation");
        }
        var row = new WorkflowDelegation();
        row.setOwnerUserId(owner);
        row.setDelegateUserId(input.delegateUserId());
        row.setStartsAt(input.startsAt());
        row.setEndsAt(input.endsAt());
        row.setCreatedAt(now());
        delegations.insertSelective(row);
        log.info("委托创建，delegationId={}，委托人 ID={}，受托人 ID={}，区间=[{}, {})", row.getId(), owner,
                input.delegateUserId(), input.startsAt(), input.endsAt());
        return view(row);
    }

    /**
     * 终止未来的自动双发，已经分派的任务继续按照原节点规则处理。
     * 已取消或已结束的委托幂等返回当前状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public Delegation cancel(int owner, long id) {
        lockUser(owner);
        var row = owned(id, owner, true);
        if (row.getCancelledAt() == null && row.getEndsAt().isAfter(now())) {
            row.setCancelledAt(now());
            delegations.update(row);
            log.info("委托取消，delegationId={}，委托人 ID={}", id, owner);
        }
        return view(row);
    }

    /**
     * 委托区间使用的业务时区（Asia/Shanghai）当前时间。
     */
    java.time.LocalDateTime now() {
        return clock.now().atOffset(java.time.ZoneOffset.UTC).atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime();
    }

    private void lockUser(int owner) {
        if (users.selectOneByQuery(QueryWrapper.create().select(USER.ID).from(USER).where(USER.ID.eq(owner)).forUpdate()) == null) {
            throw new TodoNotFoundException();
        }
    }
}
