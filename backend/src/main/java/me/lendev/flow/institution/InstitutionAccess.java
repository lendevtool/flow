package me.lendev.flow.institution;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import me.lendev.flow.common.auth.RoleType;
import me.lendev.flow.openapi.ManagedReadInvocation;
import me.lendev.flow.pms.service.PermissionService;
import me.lendev.flow.pms.service.RoleService;
import org.springframework.stereotype.Component;

/**
 * 机构页面、写操作和流程候选人授权。
 */
@Component
@RequiredArgsConstructor
class InstitutionAccess {

    static final String VIEW = "InstitutionView";
    static final String CREATE = "InstitutionCreate";
    static final String EDIT = "InstitutionEdit";
    static final String STATUS = "InstitutionStatus";
    static final String SUBMIT = "InstitutionSubmit";

    private final RoleService roles;
    private final PermissionService permissions;

    static void permitRead() {
        if (ManagedReadInvocation.callerForModule(InstitutionReadController.class) == null) {
            permit(VIEW);
        }
    }

    static int permit(String action) {
        StpUtil.checkLogin();
        if (!StpUtil.hasRole(RoleType.SUPER_ADMIN)) {
            StpUtil.checkPermission(VIEW);
            if (!VIEW.equals(action)) {
                StpUtil.checkPermission(action);
            }
        }
        return StpUtil.getLoginIdAsInt();
    }

    static boolean current(int actor, String permission) {
        return String.valueOf(actor).equals(String.valueOf(StpUtil.getLoginIdDefaultNull()))
                && (StpUtil.hasRole(RoleType.SUPER_ADMIN) || StpUtil.hasPermission(permission));
    }

    boolean candidate(int actor) {
        // 分派运行在发起人或系统上下文，取候选人的启用角色权限。
        return roles.findRolesByUserId((long) actor).stream()
                .filter(role -> Boolean.TRUE.equals(role.getEnable()))
                .anyMatch(role -> RoleType.SUPER_ADMIN.equals(role.getCode())
                        || permissions.findByRoleId(role.getId()).stream().anyMatch(permission ->
                        Boolean.TRUE.equals(permission.getEnable()) && VIEW.equals(permission.getCode())));
    }
}
