package me.lendev.flow.pms.service.support;

import static me.lendev.flow.pms.domain.entity.table.PermissionTableDef.PERMISSION;
import static me.lendev.flow.pms.domain.entity.table.RoleTableDef.ROLE;
import static me.lendev.flow.pms.domain.entity.table.UserTableDef.USER;

import com.mybatisflex.core.query.QueryWrapper;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.ToLongFunction;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.pms.mapper.PermissionMapper;
import me.lendev.flow.pms.mapper.RoleMapper;
import me.lendev.flow.pms.mapper.UserMapper;
import org.springframework.stereotype.Component;

/**
 * 关联分配校验器：在调用方修改任何数据之前，用单条查询校验全部被引用的用户/角色/权限是否存在。 查询使用 {@code forUpdate} 加行锁，防止校验通过后目标行被并发删除，导致产生悬空关联。
 */
@Component
@RequiredArgsConstructor
public class AssignmentValidator {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;

    public void requireUsers(Collection<Long> ids) {
        requireExisting(ids, "error.user.id.invalid", "error.user.missing", values -> userMapper.selectListByQuery(
                QueryWrapper.create().select(USER.ID).where(USER.ID.in(values)).orderBy(USER.ID.asc()).forUpdate()).size());
    }

    public void requireRoles(Collection<Long> ids) {
        requireExisting(ids, "error.role.id.invalid", "error.role.missing", values -> roleMapper.selectListByQuery(
                QueryWrapper.create().select(ROLE.ID).where(ROLE.ID.in(values)).orderBy(ROLE.ID.asc()).forUpdate()).size());
    }

    public void requirePermissions(Collection<Long> ids) {
        requireExisting(ids, "error.permission.id.invalid", "error.permission.missing", values -> permissionMapper.selectListByQuery(
                QueryWrapper.create().select(PERMISSION.ID).where(PERMISSION.ID.in(values)).orderBy(PERMISSION.ID.asc()).forUpdate()).size());
    }

    private void requireExisting(Collection<Long> ids, String invalidIdKey, String missingKey,
                                 ToLongFunction<Set<Long>> countExisting) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Set<Long> distinctIds = new HashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                throw BadRequestException.localized(invalidIdKey);
            }
            distinctIds.add(id);
        }
        if (countExisting.applyAsLong(distinctIds) != distinctIds.size()) {
            throw BadRequestException.localized(missingKey);
        }
    }
}
