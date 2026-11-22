package me.lendev.flow.pms.audit;

import static me.lendev.flow.pms.domain.entity.table.RoleTableDef.ROLE;
import static me.lendev.flow.pms.domain.entity.table.RolePermissionTableDef.ROLE_PERMISSION;
import static me.lendev.flow.pms.domain.entity.table.UserRoleTableDef.USER_ROLE;

import com.mybatisflex.core.query.QueryWrapper;

import java.util.*;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.*;
import me.lendev.flow.audit.model.*;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.pms.domain.entity.Role;
import me.lendev.flow.pms.domain.entity.UserRole;
import me.lendev.flow.pms.domain.request.*;
import me.lendev.flow.pms.mapper.*;
import org.springframework.stereotype.Component;

/**
 * 角色操作的审计处理器，覆盖角色的增删改及角色-权限、角色-用户关联变更。 关联集合（权限/用户 id）变化会影响多个对象，因此除主变更记录外， 还为每个受影响用户追加一条引用型审计条目；查询使用 {@code forUpdate} 加锁保证前后快照一致。
 */
@Component
@RequiredArgsConstructor
public class RoleAuditHandler implements AuditHandler {

    private final RoleMapper roles;
    private final RolePermissionMapper permissions;
    private final UserRoleMapper users;
    private final PmsAuditSnapshots captures;
    private final AuditSnapshots changes;

    public Set<AuditOperation> operations() {
        return EnumSet.range(AuditOperation.ROLE_CREATE, AuditOperation.ROLE_USER_REMOVE);
    }

    @Override
    public void validate(java.lang.reflect.Method method) {
        switch (method.getAnnotation(Audited.class).value()) {
            case ROLE_CREATE -> PmsAuditSnapshots.binding(method, CreateRoleRequest.class);
            case ROLE_UPDATE -> PmsAuditSnapshots.binding(method, Long.class, UpdateRoleRequest.class);
            case ROLE_DELETE -> PmsAuditSnapshots.binding(method, Long.class);
            case ROLE_PERMISSION_ADD -> PmsAuditSnapshots.binding(method, AddRolePermissionsRequest.class);
            case ROLE_USER_ADD -> PmsAuditSnapshots.binding(method, Long.class, AddRoleUsersRequest.class);
            case ROLE_USER_REMOVE -> PmsAuditSnapshots.binding(method, Long.class, RemoveRoleUsersRequest.class);
            default -> throw new IllegalStateException("角色操作未注册");
        }
    }

    public Plan prepare(AuditOperation operation, Object[] args) {
        if (operation == AuditOperation.ROLE_CREATE) {
            return prepareCreation(((CreateRoleRequest) args[0]).getCode());
        }
        Long id = operation == AuditOperation.ROLE_PERMISSION_ADD ? ((AddRolePermissionsRequest) args[0]).getId() : (Long) args[0];
        Role role = require(id);
        boolean membership = operation == AuditOperation.ROLE_USER_ADD || operation == AuditOperation.ROLE_USER_REMOVE;
        boolean deleting = operation == AuditOperation.ROLE_DELETE;
        List<Long> oldUsers = membership || deleting ? userIds(id) : null;
        var before = captures.role(role, membership ? null : permissionIds(id), oldUsers);
        return result -> roleChanges(id, before, oldUsers, membership, deleting);
    }

    private List<AuditChange> roleChanges(Long id, AuditSnapshots.Capture before, List<Long> oldUsers,
                                          boolean membership, boolean deleting) {
        AuditChange changed;
        if (deleting) {
            changed = deletedRole(id, before);
        } else {
            Role current = require(id);
            var currentPermissions = membership ? null : permissionIds(id);
            var currentUsers = membership ? userIds(id) : null;
            changed = changes.updated(before, captures.role(current, currentPermissions, currentUsers));
        }
        List<AuditChange> entries = new ArrayList<>();
        entries.add(changed);
        if (oldUsers != null) {
            appendUserChanges(entries, oldUsers, membership ? userIds(id) : List.of());
        }
        return entries;
    }

    private Plan prepareCreation(String code) {
        return result -> {
            Role created = roles.selectOneByQuery(QueryWrapper.create().where(ROLE.CODE.eq(code)).forUpdate());
            if (created == null) {
                throw new IllegalStateException("角色创建结果不存在");
            }
            return List.of(changes.created(captures.role(created, permissionIds(created.getId()), null)));
        };
    }

    private AuditChange deletedRole(Long id, AuditSnapshots.Capture before) {
        if (find(id) != null) {
            throw new IllegalStateException("角色删除结果不一致");
        }
        return changes.deleted(before);
    }

    private void appendUserChanges(List<AuditChange> entries, List<Long> oldUsers, List<Long> currentUsers) {
        var related = new ArrayList<>(oldUsers);
        related.addAll(currentUsers);
        for (String user : PmsAuditSnapshots.ids(related.stream().distinct().toList())) {
            entries.add(changes.reference(new AuditObject("USER", user, null, user, null, null)));
        }
    }

    private List<Long> permissionIds(Long id) {
        return permissions.selectListByQuery(QueryWrapper.create().where(ROLE_PERMISSION.ROLE_ID.eq(id))
                .orderBy(ROLE_PERMISSION.PERMISSION_ID.asc()).limit(5001).forUpdate()).stream().map(row -> row.getPermissionId()).toList();
    }

    private List<Long> userIds(Long id) {
        return users.selectListByQuery(QueryWrapper.create().where(USER_ROLE.ROLE_ID.eq(id))
                .orderBy(USER_ROLE.USER_ID.asc()).limit(5001).forUpdate()).stream().map(UserRole::getUserId).toList();
    }

    private Role require(Long id) {
        if (id == null || id <= 0) {
            throw BadRequestException.localized("error.role.id.invalid");
        }
        Role role = find(id);
        if (role == null) {
            throw BadRequestException.localized("error.role.missing");
        }
        return role;
    }

    private Role find(Long id) {
        return roles.selectOneByQuery(QueryWrapper.create().where(ROLE.ID.eq(id)).forUpdate());
    }
}
