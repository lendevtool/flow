package me.lendev.flow.pms.audit;

import static me.lendev.flow.pms.domain.entity.table.UserTableDef.USER;
import static me.lendev.flow.pms.domain.entity.table.ProfileTableDef.PROFILE;
import static me.lendev.flow.pms.domain.entity.table.UserRoleTableDef.USER_ROLE;

import cn.dev33.satoken.stp.StpUtil;
import com.mybatisflex.core.query.QueryWrapper;

import java.util.*;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.audit.*;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.pms.domain.entity.User;
import me.lendev.flow.pms.domain.entity.UserRole;
import me.lendev.flow.pms.department.DepartmentAuditHandler;
import me.lendev.flow.pms.department.DepartmentRepository;
import me.lendev.flow.pms.domain.dto.TemporaryPasswordDto;
import me.lendev.flow.pms.domain.request.*;
import me.lendev.flow.pms.mapper.*;
import org.springframework.stereotype.Component;

/**
 * 用户操作的审计处理器，覆盖用户增删改、资料更新、角色替换与密码重置/修改。 密码属于凭据，不进入字段级快照，仅记录引用型审计条目（谁在何时对哪个用户操作了密码）。
 */
@Component
@RequiredArgsConstructor
public class UserAuditHandler implements AuditHandler {

    private final UserMapper users;
    private final ProfileMapper profiles;
    private final UserRoleMapper roles;
    private final PmsAuditSnapshots captures;
    private final AuditSnapshots changes;
    private final DepartmentRepository departments;
    private final DepartmentAuditHandler departmentAudit;

    public Set<AuditOperation> operations() {
        return EnumSet.of(AuditOperation.USER_CREATE, AuditOperation.USER_UPDATE, AuditOperation.USER_DELETE,
                AuditOperation.USER_PROFILE_UPDATE, AuditOperation.USER_ROLE_REPLACE,
                AuditOperation.USER_PASSWORD_RESET, AuditOperation.USER_PASSWORD_CHANGE);
    }

    @Override
    public void validate(java.lang.reflect.Method method) {
        switch (method.getAnnotation(Audited.class).value()) {
            case USER_CREATE ->
                    PmsAuditSnapshots.bindingResult(method, TemporaryPasswordDto.class, RegisterUserRequest.class);
            case USER_UPDATE -> PmsAuditSnapshots.binding(method, Long.class, UpdateUserRequest.class);
            case USER_DELETE -> PmsAuditSnapshots.binding(method, Long.class);
            case USER_PROFILE_UPDATE -> PmsAuditSnapshots.binding(method, Long.class, UpdateProfileRequest.class);
            case USER_ROLE_REPLACE -> PmsAuditSnapshots.binding(method, Long.class, AddUserRolesRequest.class);
            case USER_PASSWORD_RESET -> PmsAuditSnapshots.bindingResult(method, TemporaryPasswordDto.class, Long.class);
            case USER_PASSWORD_CHANGE -> PmsAuditSnapshots.binding(method, ChangePasswordRequest.class);
            default -> throw new IllegalStateException("用户操作未注册");
        }
    }

    public Plan prepare(AuditOperation operation, Object[] args) {
        if (operation == AuditOperation.USER_CREATE) {
            return prepareCreation((RegisterUserRequest) args[0]);
        }
        Long id = operation == AuditOperation.USER_PASSWORD_CHANGE ? StpUtil.getLoginIdAsLong() : (Long) args[0];
        if (operation == AuditOperation.USER_PASSWORD_CHANGE || operation == AuditOperation.USER_PASSWORD_RESET) {
            // 密码修改依赖业务层已有的乐观身份校验；在校验旧密码之前不能加行锁，避免锁住尚不合法的请求。
            User user = require(id, operation == AuditOperation.USER_PASSWORD_RESET);
            var target = captures.userRef(user);
            return result -> List.of(changes.reference(target));
        }
        return prepareUserChange(operation, args, id);
    }

    private Plan prepareCreation(RegisterUserRequest request) {
        String username = request.getUsername();
        boolean includesMemberships = request.getMemberships() != null;
        if (includesMemberships) {
            departments.lockWrites();
        }
        return result -> {
            User user = users.selectOneByQuery(QueryWrapper.create().where(USER.USERNAME.eq(username)).forUpdate());
            if (user == null) {
                throw new IllegalStateException("用户创建结果不存在");
            }
            return List.of(changes.created(capture(user, includesMemberships)));
        };
    }

    private Plan prepareUserChange(AuditOperation operation, Object[] args, Long id) {
        boolean deleting = operation == AuditOperation.USER_DELETE;
        boolean leadershipAffected = deleting || operation == AuditOperation.USER_UPDATE
                && Boolean.FALSE.equals(((UpdateUserRequest) args[1]).getEnable());
        boolean includesMemberships = deleting || operation == AuditOperation.USER_UPDATE
                && ((UpdateUserRequest) args[1]).getMemberships() != null;
        if (leadershipAffected || includesMemberships) {
            departments.lockWrites();
        }
        var before = capture(require(id, true), includesMemberships);
        Plan leadership = leadershipAffected ? departmentAudit.leadershipChanges(id) : result -> List.of();
        return result -> {
            if (deleting && find(id, true) != null) {
                throw new IllegalStateException("用户删除结果不一致");
            }
            var entries = new ArrayList<>(leadership.after(result));
            entries.addFirst(deleting
                    ? changes.deleted(before) : changes.updated(before, capture(require(id, true), includesMemberships)));
            return entries;
        };
    }

    private AuditSnapshots.Capture capture(User user, boolean includesMemberships) {
        var profile = profiles.selectOneByQuery(QueryWrapper.create().where(PROFILE.USER_ID.eq(user.getId())).forUpdate());
        var roleIds = roles.selectListByQuery(QueryWrapper.create().where(USER_ROLE.USER_ID.eq(user.getId()))
                .orderBy(USER_ROLE.ROLE_ID.asc()).limit(5001).forUpdate()).stream().map(UserRole::getRoleId).toList();
        var memberships = includesMemberships ? departments.memberships(List.of(user.getId()))
                .getOrDefault(user.getId(), List.of()) : null;
        return captures.user(user, profile, roleIds, memberships);
    }

    private User require(Long id, boolean lock) {
        if (id == null || id <= 0) {
            throw BadRequestException.localized("error.user.id.invalid");
        }
        User user = find(id, lock);
        if (user == null) {
            throw BadRequestException.localized("error.user.missing");
        }
        return user;
    }

    private User find(Long id, boolean lock) {
        var query = QueryWrapper.create().where(USER.ID.eq(id));
        return users.selectOneByQuery(lock ? query.forUpdate() : query);
    }
}
