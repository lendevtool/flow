package me.lendev.flow.workbench.todo;

import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO;
import static me.lendev.flow.common.auth.PermissionCode.WORKBENCH_TODO_CALENDAR;

import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.stp.StpUtil;
import me.lendev.flow.common.auth.RoleType;

/**
 * 工作台共用的登录与菜单权限入口。
 */
public final class TodoAccess {

    private TodoAccess() {
    }

    /**
     * 返回当前登录用户 ID，未登录时由 Sa-Token 抛出异常。
     */
    public static int viewer() {
        return StpUtil.getLoginIdAsInt();
    }

    /**
     * 无请求上下文或未登录时返回空，供无 HTTP 会话的写入路径使用。
     */
    static Integer viewerOrNull() {
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            return loginId == null ? null : Integer.valueOf(loginId.toString());
        } catch (SaTokenContextException noContext) {
            return null;
        }
    }

    /**
     * 判断当前用户是否具备权限，超级管理员恒为 true。
     */
    public static boolean can(String permission) {
        return StpUtil.hasRole(RoleType.SUPER_ADMIN) || StpUtil.hasPermission(permission);
    }

    /**
     * 校验登录与权限，超级管理员跳过权限码检查，不通过时抛出异常。
     */
    public static void permit(String permission) {
        StpUtil.checkLogin();
        if (!StpUtil.hasRole(RoleType.SUPER_ADMIN)) {
            StpUtil.checkPermission(permission);
        }
    }

    static void permitRead() {
        if (!can(WORKBENCH_TODO_CALENDAR)) {
            permit(WORKBENCH_TODO);
        }
    }
}
