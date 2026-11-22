package me.lendev.flow.audit.model;

/**
 * 审计操作者身份快照：记录类型（USER/SYSTEM/ANONYMOUS）、账号与角色等安全字段。
 */
public record AuditActor(String type, String id, String account, String name,
                         String roleCode, String roleName) {

    /**
     * @return 未认证访问者的匿名身份快照
     */
    public static AuditActor anonymous() {
        return new AuditActor("ANONYMOUS", null, null, "未认证访问者", null, null);
    }

    /**
     * 构建定时任务等非 HTTP 调用方的系统身份。
     *
     * @param name 系统任务名称
     * @return 系统身份快照
     */
    public static AuditActor system(String name) {
        return new AuditActor("SYSTEM", null, null, name, null, null);
    }
}
