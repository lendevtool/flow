package me.lendev.flow.common.auth;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;

import java.util.List;

import lombok.RequiredArgsConstructor;
import me.lendev.flow.pms.domain.entity.Permission;
import me.lendev.flow.pms.domain.entity.Role;
import me.lendev.flow.pms.mapper.UserMapper;
import me.lendev.flow.pms.service.PermissionService;
import me.lendev.flow.pms.service.RoleService;
import org.springframework.stereotype.Component;

/**
 * Sa-Token 权限数据源实现。
 *
 * <p>每次鉴权都实时查询数据库中的角色分配，而不是使用登录时的快照，
 * 以保证角色/权限变更、账号禁用等操作立即生效。</p>
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final RoleService roleService;
    private final UserMapper userMapper;
    private final PermissionService permissionService;

    /**
     * 解析当前会话对应的启用角色。
     *
     * <p>以下情况返回 {@code null}（即视为无任何角色/权限）：
     * 非当前登录类型、loginId 与当前会话不一致、用户不存在、用户被禁用、 会话与 {@link AccountSession} 不匹配，或当前角色编码未被分配/已禁用。</p>
     *
     * @param loginId   登录 ID
     * @param loginType 登录类型
     * @return 匹配的启用角色，无法授权时返回 {@code null}
     */
    private Role currentRole(Object loginId, String loginType) {
        if (!StpUtil.getLoginType().equals(loginType)
                || !String.valueOf(loginId).equals(String.valueOf(StpUtil.getLoginIdDefaultNull()))) {
            return null;
        }
        var user = userMapper.selectOneById(Long.valueOf(loginId.toString()));
        if (user == null || !Boolean.TRUE.equals(user.getEnable()) || !AccountSession.matches(user)) {
            return null;
        }
        String code = SaTokenConfigure.currentRole();
        return roleService.findRolesByUserId(Long.valueOf(loginId.toString())).stream()
                .filter(role -> Boolean.TRUE.equals(role.getEnable()) && role.getCode().equals(code))
                .findFirst().orElse(null);
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Role role = currentRole(loginId, loginType);
        if (role == null) {
            return List.of();
        }
        // 超级管理员直接拥有全部权限，其他角色只取按角色分配的权限
        List<Permission> permissions = RoleType.SUPER_ADMIN.equals(role.getCode())
                ? permissionService.list() : permissionService.findByRoleId(role.getId());
        return permissions.stream().filter(p -> Boolean.TRUE.equals(p.getEnable()))
                .map(Permission::getCode).filter(code -> code != null && !code.isBlank()).distinct().toList();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Role role = currentRole(loginId, loginType);
        return role == null ? List.of() : List.of(role.getCode());
    }
}
