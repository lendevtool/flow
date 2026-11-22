package me.lendev.flow.openapi.admin;

import static me.lendev.flow.common.auth.PermissionCode.OPEN_API_VIEW;

import cn.dev33.satoken.stp.StpUtil;
import me.lendev.flow.common.auth.RoleType;

/**
 * 在开放 API 后台统一校验查看权限、操作权限和当前员工身份。
 */
final class OpenApiAdminAccess {
    private OpenApiAdminAccess() {
    }

    static void permit(String code) {
        boolean allowed = StpUtil.hasRole(RoleType.SUPER_ADMIN)
                || (StpUtil.hasPermission(OPEN_API_VIEW) && (OPEN_API_VIEW.equals(code) || StpUtil.hasPermission(code)));
        if (!allowed) {
            throw OpenApiAdminFailure.denied();
        }
    }

    static int actor() {
        return StpUtil.getLoginIdAsInt();
    }
}
