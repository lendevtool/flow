package me.lendev.flow.pms.service.impl;

import static me.lendev.flow.common.validation.EnglishName.normalizeEnglishName;
import static me.lendev.flow.pms.domain.entity.table.RoleTableDef.ROLE;
import static me.lendev.flow.pms.domain.entity.table.RolePermissionTableDef.ROLE_PERMISSION;
import static me.lendev.flow.pms.domain.entity.table.UserRoleTableDef.USER_ROLE;

import com.mybatisflex.core.query.QueryWrapper;
import me.lendev.flow.audit.Audited;
import me.lendev.flow.audit.AuditOperation;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.exception.ResourceNotFoundException;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.pms.convert.PermissionConverter;
import me.lendev.flow.pms.convert.RoleConverter;
import me.lendev.flow.pms.domain.dto.PermissionDto;
import me.lendev.flow.pms.domain.dto.RoleCatalogDto;
import me.lendev.flow.pms.domain.dto.RolePageDto;
import me.lendev.flow.pms.domain.entity.Permission;
import me.lendev.flow.pms.domain.entity.Role;
import me.lendev.flow.pms.domain.entity.RolePermission;
import me.lendev.flow.pms.domain.entity.UserRole;
import me.lendev.flow.pms.domain.request.AddRolePermissionsRequest;
import me.lendev.flow.pms.domain.request.AddRoleUsersRequest;
import me.lendev.flow.pms.domain.request.CreateRoleRequest;
import me.lendev.flow.pms.domain.request.RemoveRoleUsersRequest;
import me.lendev.flow.pms.domain.request.RolePageRequest;
import me.lendev.flow.pms.domain.request.UpdateRoleRequest;
import me.lendev.flow.pms.mapper.RoleMapper;
import me.lendev.flow.pms.service.PermissionService;
import me.lendev.flow.pms.service.RolePermissionService;
import me.lendev.flow.pms.service.RoleService;
import me.lendev.flow.pms.service.UserRoleService;
import me.lendev.flow.pms.util.PermissionUtil;
import me.lendev.flow.pms.service.support.AssignmentValidator;
import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.text.CharSequenceUtil;
import com.mybatisflex.spring.service.impl.ServiceImpl;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.lendev.flow.audit.AuditParticipants;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色服务实现类
 *
 * @author lendev
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoleServiceImpl extends ServiceImpl<RoleMapper, Role> implements RoleService {

    private final PermissionService permissionService;

    private final RolePermissionService rolePermissionService;

    private final UserRoleService userRoleService;

    private final RoleConverter roleConverter;

    private final PermissionConverter permissionConverter;

    private final AssignmentValidator assignmentValidator;

    @Override
    public List<Role> findRolesByUserId(Long userId) {
        return getMapper().findRolesByUserId(userId);
    }

    @Override
    public List<Tree<Long>> findRolePermissionsTree(String roleCode) {
        Role role = this.findByCode(roleCode);
        if (role == null || !Boolean.TRUE.equals(role.getEnable())) {
            throw BadRequestException.localized("error.role.current.unavailable");
        }
        List<Permission> allPermissions = permissionService.list();
        Map<Long, Permission> permissionsById = allPermissions.stream()
                .collect(Collectors.toMap(Permission::getId, permission -> permission));
        // 超级管理员不走关联表，直接拥有全部权限
        List<Permission> permissions = RoleType.SUPER_ADMIN.equals(roleCode) ? allPermissions
                : permissionService.findByRoleId(role.getId());
        // 任一祖先被禁用则整条分支不可见，避免前端渲染出无法访问的菜单
        List<Permission> enabledPermissions = permissions.stream()
                .filter(permission -> hasEnabledAncestors(permission, permissionsById)).toList();
        return PermissionUtil.toTreeNode(enabledPermissions, null);
    }

    @Override
    public Role findByCode(String roleCode) {
        return queryChain().and(ROLE.CODE.eq(roleCode, true)).one();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.ROLE_CREATE)
    public void createRole(CreateRoleRequest request) {
        boolean exists = queryChain().and(ROLE.CODE.eq(request.getCode(), true))
                .or(ROLE.NAME.eq(request.getName(), true))
                .exists();

        if (exists) {
            throw BadRequestException.localized("error.role.duplicate");
        }

        assignmentValidator.requirePermissions(request.getPermissionIds());
        Role role = roleConverter.toRole(request);
        role.setNameEn(normalizeEnglishName(request.getNameEn()));
        save(role);
        List<Long> permissionIds = request.getPermissionIds() == null ? List.of() : request.getPermissionIds();
        List<RolePermission> permissionList = permissionIds.stream().distinct()
                .map(permId -> {
                    RolePermission rolePermission = new RolePermission();
                    rolePermission.setRoleId(role.getId());
                    rolePermission.setPermissionId(permId);
                    return rolePermission;
                }).toList();
        if (!permissionList.isEmpty()) {
            rolePermissionService.saveBatch(permissionList);
        }
        log.info("角色已写入，requestId={} roleId={} permissionCount={}",
                AuditParticipants.currentRequestId(), role.getId(), permissionList.size());
    }

    @Override
    public Page<RolePageDto> queryPage(RolePageRequest request) {
        var roles = getMapper().pageRoles(request.toPage(), request.getName(), request.getEnable());
        List<Long> roleIds = roles.getRecords().stream().map(Role::getId).toList();
        Map<Long, List<Long>> permissionsByRole = roleIds.isEmpty() ? Map.of()
                : rolePermissionService.queryChain()
                .and(ROLE_PERMISSION.ROLE_ID.in(roleIds)).list().stream()
                .collect(Collectors.groupingBy(RolePermission::getRoleId,
                        Collectors.mapping(RolePermission::getPermissionId, Collectors.toList())));
        com.mybatisflex.core.paginate.Page<RolePageDto> pageRet = roles.map(role -> {
            RolePageDto dto = roleConverter.toRolePageDto(role);
            dto.setPermissionIds(permissionsByRole.getOrDefault(role.getId(), List.of()));
            return dto;
        });

        return Page.convert(pageRet);
    }

    @Override
    public Page<RoleCatalogDto> catalogPage(String name, Boolean enable, int pageNo, int pageSize) {
        var page = com.mybatisflex.core.paginate.Page.<Role>of(pageNo, pageSize);
        return Page.convert(getMapper().pageRoles(page, name, enable).map(RoleServiceImpl::toCatalogEntry));
    }

    @Override
    public RoleCatalogDto catalogEntry(long id) {
        Role role = getById(id);
        if (role == null) {
            throw new ResourceNotFoundException();
        }
        return toCatalogEntry(role);
    }

    private static RoleCatalogDto toCatalogEntry(Role role) {
        return new RoleCatalogDto(role.getId(), role.getCode(), role.getName(), role.getNameEn(),
                Boolean.TRUE.equals(role.getEnable()));
    }

    @Override
    public List<PermissionDto> findRolePermissions(Long id) {
        requireRole(id);
        return permissionService.findByRoleId(id)
                .stream()
                .map(permissionConverter::toPermissionDto)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.ROLE_UPDATE)
    public void updateRole(Long id, UpdateRoleRequest request) {
        Role role = requireRole(id);
        if (RoleType.SUPER_ADMIN.equals(role.getCode())) {
            throw BadRequestException.localized("error.role.super-admin.immutable");
        }
        assignmentValidator.requirePermissions(request.getPermissionIds());
        if (CharSequenceUtil.isNotBlank(request.getName())) {
            if (queryChain().and(ROLE.NAME.eq(request.getName(), true))
                    .and(ROLE.ID.ne(id, true)).exists()) {
                throw BadRequestException.localized("error.role.duplicate");
            }
            role.setName(request.getName());
        }
        if (ObjectUtil.isNotNull(request.getEnable())) {
            role.setEnable(request.getEnable());
        }
        if (request.getNameEn() != null) {
            role.setNameEn(normalizeEnglishName(request.getNameEn()));
        }
        // role 是加锁读取的完整记录，允许将已清除的英文名写为 NULL。
        getMapper().update(role, false);
        if (request.getPermissionIds() != null) {
            rolePermissionService.remove(QueryWrapper.create().and(ROLE_PERMISSION.ROLE_ID.eq(id, true)));
            if (!request.getPermissionIds().isEmpty()) {
                List<RolePermission> permissionList = request.getPermissionIds().stream().distinct()
                        .map(permId -> {
                            RolePermission rolePermission = new RolePermission();
                            rolePermission.setRoleId(id);
                            rolePermission.setPermissionId(permId);
                            return rolePermission;
                        }).toList();
                rolePermissionService.saveBatch(permissionList);
            }
        }
        log.info("角色已更新，requestId={} roleId={} enabled={} permissionsReplaced={}",
                AuditParticipants.currentRequestId(), id, role.getEnable(), request.getPermissionIds() != null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.ROLE_DELETE)
    public void removeRole(Long id) {
        Role role = requireRole(id);
        if (RoleType.SUPER_ADMIN.equals(role.getCode())) {
            throw BadRequestException.localized("error.role.super-admin.immutable");
        }
        userRoleService.remove(QueryWrapper.create().and(USER_ROLE.ROLE_ID.eq(id, true)));
        rolePermissionService.remove(QueryWrapper.create().and(ROLE_PERMISSION.ROLE_ID.eq(id, true)));
        removeById(id);
        log.info("角色及其授权关联已删除，requestId={} roleId={}", AuditParticipants.currentRequestId(), id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.ROLE_PERMISSION_ADD)
    public void addRolePermissions(AddRolePermissionsRequest request) {
        Role role = requireRole(request.getId());
        if (RoleType.SUPER_ADMIN.equals(role.getCode())) {
            throw BadRequestException.localized("error.role.super-admin.grant");
        }
        if (request.getPermissionIds() == null) {
            throw BadRequestException.localized("error.assignment.permissions.empty");
        }
        assignmentValidator.requirePermissions(request.getPermissionIds());
        // 加锁读取已有关联并去重，防止并发请求重复插入同一 (roleId, permissionId)
        Set<Long> existingIds = new HashSet<>(rolePermissionService.queryChain()
                .select(ROLE_PERMISSION.PERMISSION_ID)
                .and(ROLE_PERMISSION.ROLE_ID.eq(role.getId(), true))
                .orderBy(ROLE_PERMISSION.PERMISSION_ID.asc()).forUpdate().list()
                .stream()
                .map(RolePermission::getPermissionId).toList());

        List<RolePermission> permissionList = request.getPermissionIds()
                .stream().distinct().filter(id -> !existingIds.contains(id))
                .map(permId -> {
                    RolePermission rolePermission = new RolePermission();
                    rolePermission.setRoleId(role.getId());
                    rolePermission.setPermissionId(permId);
                    return rolePermission;
                }).toList();
        if (!permissionList.isEmpty()) {
            rolePermissionService.saveBatch(permissionList);
        }

    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.ROLE_USER_ADD)
    public void addRoleUsers(Long roleId, AddRoleUsersRequest request) {
        requireRole(roleId);
        if (request.getUserIds() == null) {
            throw BadRequestException.localized("error.assignment.users.empty");
        }
        assignmentValidator.requireUsers(request.getUserIds());
        // 加锁读取已有关联并去重，防止并发请求重复插入同一 (roleId, userId)
        Set<Long> existingIds = new HashSet<>(userRoleService.queryChain()
                .select(USER_ROLE.USER_ID)
                .and(USER_ROLE.ROLE_ID.eq(roleId, true))
                .orderBy(USER_ROLE.USER_ID.asc()).forUpdate().list()
                .stream()
                .map(UserRole::getUserId).toList());

        List<UserRole> permissionList = request.getUserIds()
                .stream().distinct().filter(id -> !existingIds.contains(id))
                .map(userId -> {
                    UserRole userRole = new UserRole();
                    userRole.setRoleId(roleId);
                    userRole.setUserId(userId);
                    return userRole;
                }).toList();
        if (!permissionList.isEmpty()) {
            userRoleService.saveBatch(permissionList);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(AuditOperation.ROLE_USER_REMOVE)
    public void removeRoleUsers(Long roleId, RemoveRoleUsersRequest request) {
        requireRole(roleId);
        if (request.getUserIds() == null) {
            throw BadRequestException.localized("error.assignment.users.empty");
        }
        assignmentValidator.requireUsers(request.getUserIds());
        if (request.getUserIds().isEmpty()) {
            return;
        }
        userRoleService.remove(QueryWrapper.create()
                .and(USER_ROLE.ROLE_ID.eq(roleId, true))
                .and(USER_ROLE.USER_ID.in(request.getUserIds())));
    }

    private Role requireRole(Long id) {
        if (id == null || id <= 0) {
            throw BadRequestException.localized("error.role.id.invalid");
        }
        Role role = getMapper().selectOneByQuery(QueryWrapper.create().where(ROLE.ID.eq(id)).forUpdate());
        if (role == null) {
            throw BadRequestException.localized("error.role.missing");
        }
        return role;
    }

    /**
     * 判断权限自身及其全部祖先是否均启用；visited 用于防御存量脏数据成环导致死循环。
     */
    private boolean hasEnabledAncestors(Permission permission, Map<Long, Permission> permissions) {
        Set<Long> visited = new HashSet<>();
        Permission current = permission;
        while (current != null) {
            if (!Boolean.TRUE.equals(current.getEnable()) || !visited.add(current.getId())) {
                return false;
            }
            if (current.getParentId() == null) {
                return true;
            }
            current = permissions.get(current.getParentId());
        }
        return false;
    }
}
