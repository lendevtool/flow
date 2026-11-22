package me.lendev.flow.pms.department;

import static me.lendev.flow.pms.department.DepartmentModels.*;
import static me.lendev.flow.pms.department.DepartmentService.*;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.*;
import me.lendev.flow.audit.model.AuditChange;
import me.lendev.flow.audit.model.AuditObject;
import me.lendev.flow.pms.audit.PmsAuditSnapshots;
import org.springframework.stereotype.Component;

/**
 * 在持有组织锁的业务事务内采集部门及成员变更。
 */
@Component
@RequiredArgsConstructor
public class DepartmentAuditHandler implements AuditHandler {

    private final DepartmentRepository repository;
    private final AuditSnapshots changes;

    @Override
    public Set<AuditOperation> operations() {
        return EnumSet.of(AuditOperation.DEPARTMENT_CREATE, AuditOperation.DEPARTMENT_UPDATE,
                AuditOperation.DEPARTMENT_LEADER_UPDATE, AuditOperation.DEPARTMENT_MEMBERS_UPDATE,
                AuditOperation.DEPARTMENT_MEMBERS_ADD, AuditOperation.DEPARTMENT_MEMBERS_REMOVE,
                AuditOperation.DEPARTMENT_MEMBERS_TRANSFER, AuditOperation.DEPARTMENT_MEMBERSHIPS_REPLACE);
    }

    @Override
    public void validate(Method method) {
        switch (method.getAnnotation(Audited.class).value()) {
            case DEPARTMENT_CREATE -> PmsAuditSnapshots.bindingResult(method, Long.class, DepartmentRequest.class);
            case DEPARTMENT_UPDATE -> PmsAuditSnapshots.binding(method, Long.class, DepartmentRequest.class);
            case DEPARTMENT_LEADER_UPDATE -> PmsAuditSnapshots.binding(method, Long.class, LeaderRequest.class);
            case DEPARTMENT_MEMBERS_UPDATE -> PmsAuditSnapshots.binding(method, MembersRequest.class);
            case DEPARTMENT_MEMBERS_ADD, DEPARTMENT_MEMBERS_REMOVE, DEPARTMENT_MEMBERS_TRANSFER ->
                    PmsAuditSnapshots.binding(method, MemberChangeRequest.class);
            case DEPARTMENT_MEMBERSHIPS_REPLACE ->
                    PmsAuditSnapshots.binding(method, Long.class, ReplaceMembershipsRequest.class);
            default -> throw new IllegalStateException("组织操作未注册");
        }
    }

    @Override
    public Plan prepare(AuditOperation operation, Object[] args) {
        repository.lockWrites();
        if (operation == AuditOperation.DEPARTMENT_CREATE) {
            return result -> List.of(changes.created(department((Long) result, index(repository.list()))));
        }
        return switch (operation) {
            case DEPARTMENT_MEMBERS_UPDATE -> membershipChanges(((MembersRequest) args[0]).userIds(), List.of());
            case DEPARTMENT_MEMBERSHIPS_REPLACE -> membershipChanges(List.of((Long) args[0]), List.of());
            case DEPARTMENT_MEMBERS_ADD, DEPARTMENT_MEMBERS_REMOVE, DEPARTMENT_MEMBERS_TRANSFER -> {
                var request = (MemberChangeRequest) args[0];
                var departments = index(repository.list());
                var references = java.util.stream.Stream.of(request.sourceDepartmentId(), request.targetDepartmentId())
                        .filter(java.util.Objects::nonNull).distinct().map(departments::get)
                        .filter(java.util.Objects::nonNull)
                        .map(item -> changes.reference(new AuditObject("DEPARTMENT", item.id().toString(),
                                null, item.name(), null, null))).toList();
                yield membershipChanges(request.users().stream().map(VersionedUser::userId).toList(), references);
            }
            default -> {
                Long id = (Long) args[0];
                var before = department(id, index(repository.list()));
                yield result -> List.of(changes.updated(before, department(id, index(repository.list()))));
            }
        };
    }

    private Plan membershipChanges(List<Long> userIds, List<AuditChange> references) {
        var members = requireMembers(repository, userIds);
        var before = members.stream().collect(Collectors.toMap(Member::id, this::membership));
        var ids = members.stream().map(Member::id).toList();
        return result -> {
            var entries = new java.util.ArrayList<>(repository.members(ids).stream()
                    .map(user -> changes.updated(before.get(user.id()), membership(user)))
                    .filter(AuditChange::changed).toList());
            entries.addAll(references);
            return entries;
        };
    }

    /**
     * 为用户停用或删除捕获其负责的部门，调用方须先持有组织锁。
     */
    public Plan leadershipChanges(Long userId) {
        var led = repository.ledBy(userId);
        if (led.isEmpty()) {
            return result -> List.of();
        }
        var departments = index(repository.list());
        var before = led.stream().map(Department::id)
                .collect(Collectors.toMap(Function.identity(), id -> department(id, departments)));
        return result -> {
            var after = index(repository.list());
            return led.stream().map(Department::id)
                    .map(id -> changes.updated(before.get(id), department(id, after)))
                    .filter(AuditChange::changed).toList();
        };
    }

    private AuditSnapshots.Capture department(Long id, Map<Long, Department> departments) {
        Department department = require(departments, id);
        String path = ancestors(departments, id).reversed().stream()
                .map(key -> require(departments, key).name()).collect(Collectors.joining(" / "));
        String parentName = department.parentId() == null ? "顶级" : require(departments, department.parentId()).name();
        return changes.capture(new AuditObject("DEPARTMENT", id.toString(), null, department.name(), null, null),
                Map.of("name", AuditField.visible("部门名称", department.name()),
                        "parentId", AuditField.visible("上级部门", department.parentId(), parentName),
                        "path", AuditField.visible("部门路径", path),
                        "sortOrder", AuditField.visible("排序", department.sortOrder()),
                        "enabled", AuditField.visible("启用", department.enabled()),
                        "leaderUserId", AuditField.visible("负责人", department.leaderUserId(), department.leaderName())));
    }

    private AuditSnapshots.Capture membership(Member member) {
        return changes.capture(new AuditObject("USER", member.id().toString(), member.username(),
                        member.username(), null, null),
                Map.of("departmentId", AuditField.visible("主部门", member.departmentId(),
                                member.departmentName() == null ? "未分配部门" : member.departmentName()),
                        "departments", membershipField(member.departments())));
    }

    /**
     * 归属快照保留稳定排序的部门标识与当时的显示名称。
     */
    public static AuditField membershipField(List<DepartmentRef> departments) {
        String display = departments.isEmpty() ? "未分配部门"
                : departments.stream().map(DepartmentRef::name).collect(Collectors.joining("、"));
        return AuditField.visible("所属部门", departments, display);
    }
}
