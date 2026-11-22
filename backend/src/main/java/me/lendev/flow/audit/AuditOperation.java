package me.lendev.flow.audit;

/**
 * 显式且稳定的业务操作注册表：SQL 语句与请求体无法凭空发明操作， 所有可审计操作都必须在此枚举中登记。
 */
public enum AuditOperation {
    DOCUMENT_UPLOAD("business.document.upload", Module.DOCUMENT_CENTER, Action.CREATE, "上传文档文件", true),
    DOCUMENT_PUBLISH("business.document.publish", Module.DOCUMENT_CENTER, Action.CREATE, "发布文档版本", false),
    DOCUMENT_EDIT("business.document.edit", Module.DOCUMENT_CENTER, Action.UPDATE, "修改文档属性", false),
    DOCUMENT_BIND("business.document.bind", Module.DOCUMENT_CENTER, Action.UPDATE, "关联业务文档", false),
    DOCUMENT_DETACH("business.document.detach", Module.DOCUMENT_CENTER, Action.UPDATE, "移除业务附件", false),
    DOCUMENT_DOWNLOAD("business.document.download", Module.DOCUMENT_CENTER, Action.DOWNLOAD, "下载文档", true),
    DOCUMENT_DELETE("business.document.delete", Module.DOCUMENT_CENTER, Action.DELETE, "删除文档", false),
    DOCUMENT_CLEANUP("business.document.cleanup", Module.DOCUMENT_CENTER, Action.DELETE, "清理文档文件", true),
    DOCGEN_TEMPLATE_CREATE("business.docgen.template.create", Module.DOCUMENT_GENERATION, Action.CREATE, "新建文档模板", false),
    DOCGEN_TEMPLATE_EDIT("business.docgen.template.edit", Module.DOCUMENT_GENERATION, Action.UPDATE, "修改文档模板", false),
    DOCGEN_VERSION_EDIT("business.docgen.version.edit", Module.DOCUMENT_GENERATION, Action.UPDATE, "维护文档模板版本", false),
    DOCGEN_VERSION_TEST("business.docgen.version.test", Module.DOCUMENT_GENERATION, Action.CREATE, "试生成文档", false),
    DOCGEN_VERSION_PUBLISH("business.docgen.version.publish", Module.DOCUMENT_GENERATION, Action.UPDATE, "发布文档模板版本", false),
    DOCGEN_CREATE("business.docgen.create", Module.DOCUMENT_GENERATION, Action.CREATE, "发起文档生成", false),
    DOCGEN_FINISH("business.docgen.finish", Module.DOCUMENT_GENERATION, Action.UPDATE, "记录文档生成结果", false),
    DOCGEN_PURGE_INPUT("business.docgen.purge-input", Module.DOCUMENT_GENERATION, Action.DELETE, "清理文档输入快照", false),
    INSTITUTION_CREATE("business.institution.create", Module.INSTITUTION, Action.CREATE, "新建机构", false),
    INSTITUTION_EDIT("business.institution.edit", Module.INSTITUTION, Action.UPDATE, "修改机构资料", false),
    INSTITUTION_STATUS("business.institution.status", Module.INSTITUTION, Action.UPDATE, "调整机构启停状态", false),
    INSTITUTION_APPLY("business.institution.apply", Module.INSTITUTION, Action.UPDATE, "应用机构资料变更", false),
    TODO_CREATE("workbench.todo.create", Module.TODO, Action.CREATE, "创建待办", false),
    TODO_EDIT("workbench.todo.edit", Module.TODO, Action.UPDATE, "编辑待办", false),
    TODO_START("workbench.todo.start", Module.TODO, Action.UPDATE, "开始待办", false),
    TODO_COMPLETE("workbench.todo.complete", Module.TODO, Action.UPDATE, "完成待办", false),
    TODO_CANCEL("workbench.todo.cancel", Module.TODO, Action.UPDATE, "取消待办", false),
    TODO_ASSIGN("workbench.todo.assign", Module.TODO, Action.UPDATE, "改派待办", false),
    TODO_PERSONAL_CONFIGURE("workbench.todo.personal.configure", Module.TODO, Action.UPDATE, "配置个人维度", false),
    TODO_PERSONAL_TAG("workbench.todo.personal.tag", Module.TODO, Action.UPDATE, "设置个人标记", false),
    TODO_SERIES_CREATE("workbench.todo.series.create", Module.TODO, Action.CREATE, "创建周期待办", false),
    TODO_SERIES_EDIT("workbench.todo.series.edit", Module.TODO, Action.UPDATE, "修改周期待办", false),
    TODO_SERIES_STOP("workbench.todo.series.stop", Module.TODO, Action.UPDATE, "停止周期待办", false),
    TODO_SERIES_GENERATE("workbench.todo.series.generate", Module.TODO, Action.CREATE, "生成周期待办", false),
    TODO_FLOW_DEFINE("workbench.flow.definition.create", Module.TODO, Action.CREATE, "创建流程模板", false),
    TODO_FLOW_DEFINE_EDIT("workbench.flow.definition.edit", Module.TODO, Action.UPDATE, "编辑流程模板", false),
    TODO_FLOW_PUBLISH("workbench.flow.definition.publish", Module.TODO, Action.UPDATE, "发布流程模板", false),
    TODO_FLOW_RETIRE("workbench.flow.definition.retire", Module.TODO, Action.UPDATE, "停用流程模板", false),
    TODO_FLOW_START("workbench.flow.start", Module.TODO, Action.CREATE, "发起流程", false),
    TODO_FLOW_COMPLETE("workbench.flow.complete", Module.TODO, Action.UPDATE, "完成流程节点", false),
    TODO_FLOW_CANCEL("workbench.flow.cancel", Module.TODO, Action.UPDATE, "取消流程", false),
    TODO_FLOW_ASSIGN("workbench.flow.assign", Module.TODO, Action.UPDATE, "改派流程节点", false),
    WORKFLOW_ACTION("workbench.flow.action", Module.TODO, Action.UPDATE, "处理角色流程", false),
    FIELD_CREATE("system.field.create", Module.FIELD, Action.CREATE, "新增字段", false),
    FIELD_UPDATE("system.field.update", Module.FIELD, Action.UPDATE, "编辑字段", false),
    FIELD_DELETE("system.field.delete", Module.FIELD, Action.DELETE, "删除字段", false),
    PARAM_GROUP_CREATE("system.param.group.create", Module.PARAM, Action.CREATE, "新建参数分类", false),
    PARAM_GROUP_UPDATE("system.param.group.update", Module.PARAM, Action.UPDATE, "修改参数分类", false),
    PARAM_GROUP_DELETE("system.param.group.delete", Module.PARAM, Action.DELETE, "删除参数分类", false),
    PARAM_GROUP_SORT("system.param.group.sort", Module.PARAM, Action.SORT, "调整参数分类排序", false),
    PARAM_ITEM_CREATE("system.param.item.create", Module.PARAM, Action.CREATE, "新建参数明细", false),
    PARAM_ITEM_UPDATE("system.param.item.update", Module.PARAM, Action.UPDATE, "修改参数明细", false),
    PARAM_ITEM_DELETE("system.param.item.delete", Module.PARAM, Action.DELETE, "删除参数明细", false),
    PARAM_ITEM_SORT("system.param.item.sort", Module.PARAM, Action.SORT, "调整参数明细排序", false),
    PARAM_CACHE_REFRESH("system.param.cache.refresh", Module.PARAM, Action.REFRESH, "刷新参数缓存", true),
    OPEN_API_ENDPOINT_STATUS("system.open-api.endpoint.status", Module.OPEN_API, Action.UPDATE, "调整开放接口发布状态", false),
    OPEN_API_ENDPOINT_IMPORT("system.open-api.endpoint.import", Module.OPEN_API, Action.CREATE, "导入只读开放接口", false),
    OPEN_API_ENDPOINT_SOURCE_CONFIRM("system.open-api.endpoint.source-confirm", Module.OPEN_API, Action.UPDATE, "重新确认开放接口来源", false),
    OPEN_API_APP_CREATE("system.open-api.app.create", Module.OPEN_API, Action.CREATE, "新建开放 API 应用", false),
    OPEN_API_APP_UPDATE("system.open-api.app.update", Module.OPEN_API, Action.UPDATE, "修改开放 API 应用", false),
    OPEN_API_APP_STATUS("system.open-api.app.status", Module.OPEN_API, Action.UPDATE, "调整开放 API 应用状态", false),
    OPEN_API_GRANT_UPDATE("system.open-api.grant.update", Module.OPEN_API, Action.UPDATE, "修改开放接口授权", false),
    OPEN_API_SECRET_GENERATE("system.open-api.secret.generate", Module.OPEN_API, Action.CREATE, "生成开放 API 候选凭据", false),
    OPEN_API_SECRET_ACTIVATE("system.open-api.secret.activate", Module.OPEN_API, Action.UPDATE, "确认开放 API 凭据切换", false),
    OPEN_API_SECRET_REVOKE("system.open-api.secret.revoke", Module.OPEN_API, Action.REVOKE, "吊销开放 API 凭据", false),
    TRADING_CALENDAR_INITIALIZE("system.trading-calendar.initialize", Module.TRADING_CALENDAR, Action.CREATE, "初始化交易日历", false),
    TRADING_CALENDAR_BATCH("system.trading-calendar.batch", Module.TRADING_CALENDAR, Action.UPDATE, "设置交易日历", false),
    TRADING_CALENDAR_IMPORT("system.trading-calendar.import", Module.TRADING_CALENDAR, Action.IMPORT, "导入交易日历", false),
    MAIL_ACCOUNT_CREATE("system.mail.account.create", Module.MAIL, Action.CREATE, "新建邮箱配置", false),
    MAIL_ACCOUNT_UPDATE("system.mail.account.update", Module.MAIL, Action.UPDATE, "修改邮箱配置", false),
    MAIL_TEMPLATE_CREATE("system.mail.template.create", Module.MAIL, Action.CREATE, "新建邮件模板", false),
    MAIL_TEMPLATE_UPDATE("system.mail.template.update", Module.MAIL, Action.UPDATE, "修改邮件模板", false),
    LOGIN("system.auth.login", Module.AUTH, Action.LOGIN, "登录", true),
    MFA_KEY_STAGE("system.auth.mfa.key.stage", Module.AUTH, Action.UPDATE, "保存 MFA 密钥草稿", false),
    MFA_KEY_REFRESH("system.auth.mfa.key.refresh", Module.AUTH, Action.UPDATE, "刷新发布 MFA 密钥", false),
    MFA_ROTATION_START("system.auth.mfa.rotation.start", Module.AUTH, Action.UPDATE, "启动 MFA 密钥轮转", false),
    MFA_ROTATION_COMPLETE("system.auth.mfa.rotation.complete", Module.AUTH, Action.UPDATE, "完成 MFA 密钥轮转", false),
    MFA_ROTATION_FAIL("system.auth.mfa.rotation.fail", Module.AUTH, Action.UPDATE, "MFA 密钥迁移失败", false),
    MFA_LOGIN("system.auth.mfa.verify", Module.AUTH, Action.VERIFY, "MFA 登录验证", true),
    MFA_BIND("system.auth.mfa.bind", Module.AUTH, Action.UPDATE, "绑定 MFA", false),
    MFA_PREFERENCE("system.auth.mfa.preference", Module.AUTH, Action.UPDATE, "修改个人 MFA", false),
    LOGOUT("system.auth.logout", Module.AUTH, Action.LOGOUT, "退出登录", true),
    AUDIT_DETAIL_VIEW("system.audit.detail.view", Module.AUDIT, Action.VIEW, "查看审计详情", true),
    AUDIT_EXPORT("system.audit.export", Module.AUDIT, Action.EXPORT, "导出操作日志", true),
    AUDIT_RETRY("system.audit.retry", Module.AUDIT, Action.RETRY, "重试审计投递", false),
    DEPARTMENT_CREATE("system.department.create", Module.DEPARTMENT, Action.CREATE, "新建部门", false),
    DEPARTMENT_UPDATE("system.department.update", Module.DEPARTMENT, Action.UPDATE, "修改部门", false),
    DEPARTMENT_LEADER_UPDATE("system.department.leader.update", Module.DEPARTMENT, Action.UPDATE, "设置部门负责人", false),
    DEPARTMENT_MEMBERS_ADD("system.department.members.add", Module.DEPARTMENT, Action.UPDATE, "添加部门成员", false),
    DEPARTMENT_MEMBERS_REMOVE("system.department.members.remove", Module.DEPARTMENT, Action.UPDATE, "移出部门成员", false),
    DEPARTMENT_MEMBERS_TRANSFER("system.department.members.transfer", Module.DEPARTMENT, Action.UPDATE, "调动部门成员", false),
    DEPARTMENT_MEMBERSHIPS_REPLACE("system.department.memberships.replace", Module.DEPARTMENT, Action.UPDATE, "编辑部门归属", false),
    DEPARTMENT_MEMBERS_UPDATE("system.department.members.update", Module.DEPARTMENT, Action.UPDATE, "调整部门成员", false),
    NOTIFICATION_GROUP_CREATE("system.notification.group.create", Module.NOTIFICATION_GROUP, Action.CREATE, "新建通知群组", false),
    NOTIFICATION_GROUP_UPDATE("system.notification.group.update", Module.NOTIFICATION_GROUP, Action.UPDATE, "修改通知群组", false),
    NOTIFICATION_GROUP_STATUS_UPDATE("system.notification.group.status.update", Module.NOTIFICATION_GROUP, Action.UPDATE, "调整通知群组状态", false),
    NOTIFICATION_SCENE_GROUPS_REPLACE("system.notification.scene.groups.replace", Module.NOTIFICATION_GROUP, Action.UPDATE, "修改通知场景绑定", false),
    WORKFLOW_NOTIFICATION_SCENE_CREATE("system.notification.workflow.scene.create", Module.NOTIFICATION_GROUP, Action.CREATE, "新建流程通知场景", false),
    WORKFLOW_NOTIFICATION_SCENE_UPDATE("system.notification.workflow.scene.update", Module.NOTIFICATION_GROUP, Action.UPDATE, "修改流程通知场景", false),
    USER_DELETE("system.user.delete", Module.USER, Action.DELETE, "删除用户", false),
    USER_SESSION_REVOKE("system.user.session.revoke", Module.USER, Action.REVOKE, "清理用户会话", true),
    ROLE_CREATE("system.role.create", Module.ROLE, Action.CREATE, "新建角色", false),
    ROLE_UPDATE("system.role.update", Module.ROLE, Action.UPDATE, "修改角色", false),
    ROLE_DELETE("system.role.delete", Module.ROLE, Action.DELETE, "删除角色", false),
    ROLE_PERMISSION_ADD("system.role.permission.add", Module.ROLE, Action.GRANT, "为角色增加权限", false),
    ROLE_USER_ADD("system.role.user.add", Module.ROLE, Action.GRANT, "为角色增加用户", false),
    ROLE_USER_REMOVE("system.role.user.remove", Module.ROLE, Action.REVOKE, "从角色移除用户", false),
    PERMISSION_CREATE("system.permission.create", Module.PERMISSION, Action.CREATE, "新建资源", false),
    PERMISSION_BATCH_CREATE("system.permission.batch.create", Module.PERMISSION, Action.CREATE, "批量新建资源", false),
    PERMISSION_UPDATE("system.permission.update", Module.PERMISSION, Action.UPDATE, "修改资源", false),
    PERMISSION_DELETE("system.permission.delete", Module.PERMISSION, Action.DELETE, "删除资源", false),
    PERMISSION_SORT("system.permission.sort", Module.PERMISSION, Action.SORT, "调整资源排序", false),
    USER_CREATE("system.user.create", Module.USER, Action.CREATE, "新建用户", false),
    USER_UPDATE("system.user.update", Module.USER, Action.UPDATE, "修改用户", false),
    USER_PROFILE_UPDATE("system.user.profile.update", Module.USER, Action.UPDATE, "修改用户资料", false),
    USER_ROLE_REPLACE("system.user.role.replace", Module.USER, Action.GRANT, "替换用户角色", false),
    USER_PASSWORD_RESET("system.user.password.reset", Module.USER, Action.RESET_PASSWORD, "重置用户密码", false),
    USER_PASSWORD_CHANGE("system.user.password.change", Module.USER, Action.CHANGE_PASSWORD, "修改本人密码", false),
    POLICY_UPDATE("system.policy.update", Module.POLICY, Action.UPDATE, "修改系统策略", false),
    POLICY_RESET("system.policy.reset", Module.POLICY, Action.RESET, "恢复系统策略默认值", false);

    private enum Module {
        FIELD, PARAM, AUTH, AUDIT, DEPARTMENT, NOTIFICATION_GROUP, USER, ROLE, PERMISSION, POLICY, TRADING_CALENDAR, TODO,
        DOCUMENT_CENTER, DOCUMENT_GENERATION, INSTITUTION, MAIL, OPEN_API
    }

    private enum Action {
        CREATE, UPDATE, DELETE, SORT, REFRESH, LOGIN, VERIFY, LOGOUT, VIEW, EXPORT, RETRY, REVOKE, GRANT, RESET_PASSWORD, CHANGE_PASSWORD, RESET, IMPORT, DOWNLOAD
    }

    private final String code;
    private final Module module;
    private final Action action;
    private final String label;
    private final boolean external;

    AuditOperation(String code, Module module, Action action, String label, boolean external) {
        this.code = code;
        this.module = module;
        this.action = action;
        this.label = label;
        this.external = external;
    }

    /**
     * @return 稳定的操作编码，作为审计记录与查询的唯一操作标识
     */
    public String code() {
        return code;
    }

    /**
     * @return 所属模块编码
     */
    public String module() {
        return module.name();
    }

    /**
     * @return 动作类型编码
     */
    public String action() {
        return action.name();
    }

    /**
     * @return 面向审计展示的中文操作标签
     */
    public String label() {
        return label;
    }

    /**
     * @return 是否为外部操作；true 时由外部执行器先持久化意图再执行业务回调，
     * false 时为数据库操作，须在可写事务内采集变更
     */
    public boolean external() {
        return external;
    }
}
