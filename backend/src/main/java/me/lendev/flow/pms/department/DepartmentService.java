package me.lendev.flow.pms.department;

import static me.lendev.flow.pms.department.DepartmentModels.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.response.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 组织架构业务规则。写方法由审计处理器在事务开始后加锁，再执行业务校验。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DepartmentService {

    private static final int MAX_DEPTH = 12;
    private final DepartmentRepository repository;

    /**
     * 查询树节点，按排序值和主键排列。
     */
    public List<Department> list() {
        return repository.list();
    }

    /**
     * 按部门、子树或空归属查询成员。
     */
    @Transactional(readOnly = true)
    public Page<Member> members(MemberQuery query) {
        if (query.departmentId() == null) {
            return repository.page(query, List.of());
        }
        if (Boolean.TRUE.equals(query.unassigned())) {
            throw BadRequestException.localized("error.department.filter");
        }
        Long id = query.departmentId();
        var departments = index(repository.list());
        require(departments, id);
        var scope = Boolean.TRUE.equals(query.includeDescendants())
                ? departments.keySet().stream().filter(candidate -> ancestors(departments, candidate).contains(id)).toList()
                : List.of(id);
        return repository.page(query, scope);
    }

    /**
     * 按名称或账号分页查询候选人。
     */
    @Transactional(readOnly = true)
    public Page<Member> candidates(MemberQuery query) {
        return repository.page(new MemberQuery(null, false, false, query.keyword(),
                query.enable(), query.pageNo(), query.pageSize()), List.of());
    }

    /**
     * 创建部门并返回生成的主键。
     */
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.DEPARTMENT_CREATE)
    public Long create(DepartmentRequest request) {
        var departments = index(repository.list());
        String name = validatePlacement(null, request, departments);
        Long id = repository.insert(request, name);
        log.info("部门已写入，requestId={} departmentId={} parentId={} enabled={}",
                AuditParticipants.currentRequestId(), id, request.parentId(), request.enabled());
        return id;
    }

    /**
     * 更新部门结构与状态，拒绝循环、超深和仍有成员或启用下级的停用请求。
     */
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.DEPARTMENT_UPDATE)
    public void update(Long id, DepartmentRequest request) {
        var departments = index(repository.list());
        var current = require(departments, id);
        String name = validatePlacement(id, request, departments);
        if (current.enabled() && !request.enabled()) {
            boolean enabledChild = departments.values().stream()
                    .anyMatch(child -> id.equals(child.parentId()) && child.enabled());
            if (enabledChild || repository.hasMembers(id)) {
                throw BadRequestException.localized("error.department.in-use");
            }
            if (current.leaderUserId() != null) {
                repository.setLeader(id, null);
            }
        }
        if (!Objects.equals(current.parentId(), request.parentId()) || !current.name().equals(name)
                || current.sortOrder() != request.sortOrder() || current.enabled() != request.enabled()) {
            repository.update(id, request, name);
            log.info("部门已更新，requestId={} departmentId={} parentId={} enabled={}",
                    AuditParticipants.currentRequestId(), id, request.parentId(), request.enabled());
        }
    }

    /**
     * 设置负责人，空值用于清空。候选人可以负责其他部门。
     */
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.DEPARTMENT_LEADER_UPDATE)
    public void setLeader(Long id, LeaderRequest request) {
        var department = require(index(repository.list()), id);
        if (request.userId() != null) {
            requireEnabled(department);
            boolean active = repository.members(List.of(request.userId())).stream().anyMatch(Member::enabled);
            if (!active) {
                throw BadRequestException.localized("error.department.leader.inactive");
            }
        }
        if (!Objects.equals(department.leaderUserId(), request.userId())) {
            repository.setLeader(id, request.userId());
            log.info("部门负责人已更新，requestId={} departmentId={} leaderUserId={}",
                    AuditParticipants.currentRequestId(), id, request.userId());
        }
    }

    /**
     * 读取指定用户的主部门、全部归属与当前版本。
     */
    @Transactional(readOnly = true)
    public Memberships memberships(Long userId) {
        var user = requireMembers(repository, List.of(userId)).getFirst();
        return new Memberships(user.departmentId(), user.departmentVersion(), user.departments());
    }

    /**
     * 兼容旧单部门调用。遇到兼岗用户时拒绝整个批次。
     */
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.DEPARTMENT_MEMBERS_UPDATE)
    public void moveMembers(MembersRequest request) {
        var users = requireMembers(repository, request.userIds());
        if (users.stream().anyMatch(user -> user.departments().size() > 1)) {
            throw new MembershipConflictException("error.department.member.legacy-conflict");
        }
        if (request.departmentId() != null) {
            requireEnabled(require(index(repository.list()), request.departmentId()));
        }
        var next = request.departmentId() == null ? Set.<Long>of() : Set.of(request.departmentId());
        users.forEach(user -> repository.replaceMemberships(user, next, request.departmentId()));
    }

    /**
     * 校验完整归属和版本后替换单个用户的部门集合。
     */
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.DEPARTMENT_MEMBERSHIPS_REPLACE)
    public void replaceMemberships(Long userId, ReplaceMembershipsRequest request) {
        applyMemberships(userId, request.departmentIds(), request.primaryDepartmentId(), request.expectedVersion());
    }

    /**
     * 在调用方持有组织锁的审计事务内应用归属。新建账号时版本参数为空。
     */
    public void applyMemberships(Long userId, List<Long> departmentIds, Long primary, Long expectedVersion) {
        var user = requireMembers(repository, List.of(userId)).getFirst();
        if (expectedVersion != null) {
            requireVersion(user, expectedVersion);
        }
        var next = new HashSet<>(departmentIds);
        if (next.size() != departmentIds.size()
                || (next.isEmpty() ? primary != null : !next.contains(primary))) {
            throw BadRequestException.localized("error.department.member.primary");
        }
        var departments = index(repository.list());
        next.forEach(id -> requireEnabled(require(departments, id)));
        repository.replaceMemberships(user, next, primary);
    }

    /**
     * 为一批账号添加同一部门，保留已有归属。
     */
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.DEPARTMENT_MEMBERS_ADD)
    public void addMembers(MemberChangeRequest request) {
        changeMembers(MemberAction.ADD, request);
    }

    /**
     * 从指定部门移出一批账号。若需要更换主部门，整批拒绝。
     */
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.DEPARTMENT_MEMBERS_REMOVE)
    public void removeMembers(MemberChangeRequest request) {
        changeMembers(MemberAction.REMOVE, request);
    }

    /**
     * 将一批账号从指定部门调至目标部门，保留其他归属。
     */
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.DEPARTMENT_MEMBERS_TRANSFER)
    public void transferMembers(MemberChangeRequest request) {
        changeMembers(MemberAction.TRANSFER, request);
    }

    private void changeMembers(MemberAction action, MemberChangeRequest request) {
        Long source = request.sourceDepartmentId();
        Long target = request.targetDepartmentId();
        validateMemberChange(action, request, source, target);
        var ids = request.users().stream().map(VersionedUser::userId).toList();
        if (ids.stream().distinct().count() != ids.size()) {
            throw BadRequestException.localized("error.department.member.duplicate");
        }
        var versions = request.users().stream().collect(Collectors.toMap(VersionedUser::userId, VersionedUser::expectedVersion));
        var users = requireMembers(repository, ids);
        var plans = new ArrayList<MembershipUpdate>();
        var needsPrimary = new ArrayList<String>();
        for (var user : users) {
            requireVersion(user, versions.get(user.id()));
            var plan = planMembershipChange(user, source, target);
            if (plan.primary() == null && !plan.departments().isEmpty()) {
                needsPrimary.add(user.username());
            }
            plans.add(plan);
        }
        if (!needsPrimary.isEmpty()) {
            throw BadRequestException.localized("error.department.member.primary-required", String.join(", ", needsPrimary));
        }
        plans.forEach(plan -> repository.replaceMemberships(plan.user(), plan.departments(), plan.primary()));
        log.info("部门成员批量变更已写入，requestId={} action={} sourceDepartmentId={} targetDepartmentId={} userCount={}",
                AuditParticipants.currentRequestId(), action, source, target, plans.size());
    }

    private void validateMemberChange(MemberAction action, MemberChangeRequest request, Long source, Long target) {
        boolean valid = switch (action) {
            case ADD -> source == null && target != null;
            case REMOVE -> source != null && target == null;
            case TRANSFER -> source != null && target != null && !source.equals(target);
        };
        if (!valid || request.action() != action) {
            throw BadRequestException.localized("error.department.member.action");
        }
        var departments = index(repository.list());
        if (source != null) {
            require(departments, source);
        }
        if (target != null) {
            requireEnabled(require(departments, target));
        }
    }

    private MembershipUpdate planMembershipChange(Member user, Long source, Long target) {
        var next = new HashSet<>(user.departments().stream().map(DepartmentRef::id).toList());
        if (source != null && !next.remove(source)) {
            throw BadRequestException.localized("error.department.member.source", user.username());
        }
        if (target != null) {
            next.add(target);
        }
        Long primary = user.departmentId();
        if (primary == null || primary.equals(source)) {
            primary = target;
        }
        return new MembershipUpdate(user, next, primary);
    }

    private record MembershipUpdate(Member user, Set<Long> departments, Long primary) {

    }

    private void requireVersion(Member user, long expectedVersion) {
        if (user.departmentVersion() != expectedVersion) {
            throw new MembershipConflictException("error.department.member.version");
        }
    }

    static List<Member> requireMembers(DepartmentRepository repository, List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_MEMBER_BATCH
                || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw BadRequestException.localized("error.department.member.batch");
        }
        var members = repository.members(ids);
        if (members.size() != ids.stream().distinct().count()) {
            throw BadRequestException.localized("error.department.member.missing");
        }
        return members;
    }

    static Map<Long, Department> index(List<Department> departments) {
        return departments.stream().collect(Collectors.toMap(Department::id, Function.identity()));
    }

    static Department require(Map<Long, Department> departments, Long id) {
        Department department = departments.get(id);
        if (department == null) {
            throw BadRequestException.localized("error.department.missing");
        }
        return department;
    }

    static List<Long> ancestors(Map<Long, Department> departments, Long id) {
        List<Long> ancestors = new ArrayList<>();
        for (Long cursor = id; cursor != null; cursor = require(departments, cursor).parentId()) {
            ancestors.add(cursor);
            if (ancestors.size() > MAX_DEPTH) {
                throw new IllegalStateException("已存部门层级超出限制");
            }
        }
        return ancestors;
    }

    private String validatePlacement(Long id, DepartmentRequest request, Map<Long, Department> departments) {
        String name = request.name().strip();
        var parentPath = ancestors(departments, request.parentId());
        if (id != null && parentPath.contains(id)) {
            throw BadRequestException.localized("error.department.cycle");
        }
        if (request.parentId() != null && (request.enabled()
                || id == null || !Objects.equals(require(departments, id).parentId(), request.parentId()))) {
            requireEnabled(require(departments, request.parentId()));
        }
        int subtreeDepth = 1;
        if (id != null && !Objects.equals(require(departments, id).parentId(), request.parentId())) {
            for (Long candidate : departments.keySet()) {
                subtreeDepth = Math.max(subtreeDepth, ancestors(departments, candidate).indexOf(id) + 1);
            }
        }
        if (parentPath.size() + subtreeDepth > MAX_DEPTH) {
            throw BadRequestException.localized("error.department.depth");
        }
        boolean duplicate = departments.values().stream().anyMatch(department -> !department.id().equals(id)
                && Objects.equals(department.parentId(), request.parentId()) && department.name().equalsIgnoreCase(name));
        if (duplicate) {
            throw BadRequestException.localized("error.department.duplicate");
        }
        return name;
    }

    private void requireEnabled(Department department) {
        if (!department.enabled()) {
            throw BadRequestException.localized("error.department.inactive");
        }
    }
}
