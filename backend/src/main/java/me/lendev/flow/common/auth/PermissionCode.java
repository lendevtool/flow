package me.lendev.flow.common.auth;

/**
 * 稳定的权限编码常量，供鉴权检查与菜单/资源配置共同使用
 */
public final class PermissionCode {

    public static final String FIELD_MGT = "FieldMgt";
    public static final String FIELD_CREATE = "FieldCreate";
    public static final String FIELD_EDIT = "FieldEdit";
    public static final String FIELD_DELETE = "FieldDelete";
    public static final String PARAM_MGT = "ParamMgt";
    public static final String PARAM_CREATE = "ParamCreate";
    public static final String PARAM_EDIT = "ParamEdit";
    public static final String PARAM_DELETE = "ParamDelete";
    public static final String PARAM_SORT = "ParamSort";
    public static final String PARAM_REFRESH = "ParamRefresh";
    public static final String MAIL_MGT = "MailMgt";
    public static final String MAIL_ACCOUNT = "MailAccount";
    public static final String MAIL_TEMPLATE = "MailTemplate";
    public static final String MAIL_LOG = "MailLog";
    public static final String MAIL_READ = "MailRead";
    public static final String MAIL_SEND = "MailSend";
    public static final String NOTIFICATION_GROUP_VIEW = "NotificationGroupView";
    public static final String NOTIFICATION_GROUP_EDIT = "NotificationGroupEdit";
    public static final String NOTIFICATION_SCENE_RECIPIENT_EDIT = "NotificationSceneRecipientEdit";
    public static final String WORKFLOW_NOTIFICATION_SCENE_EDIT = "WorkflowNotificationSceneEdit";
    public static final String WORKFLOW_NOTIFICATION_DELIVERY_VIEW = "WorkflowNotificationDeliveryView";
    public static final String OPEN_API_VIEW = "OpenApiView";
    public static final String OPEN_API_APP_MANAGE = "OpenApiAppManage";
    public static final String OPEN_API_GRANT_MANAGE = "OpenApiGrantManage";
    public static final String OPEN_API_ENDPOINT_PUBLISH = "OpenApiEndpointPublish";
    public static final String OPEN_API_SECRET_MANAGE = "OpenApiSecretManage";
    public static final String OPEN_API_CALL_LOG_VIEW = "OpenApiCallLogView";
    public static final String TRADING_CALENDAR_MGT = "TradingCalendarMgt";
    public static final String TRADING_CALENDAR_INIT = "TradingCalendarInit";
    public static final String TRADING_CALENDAR_EDIT = "TradingCalendarEdit";
    public static final String TRADING_CALENDAR_IMPORT = "TradingCalendarImport";
    public static final String DOCUMENT_CENTER_VIEW = "DocumentCenterView";
    public static final String DOCUMENT_CENTER_UPLOAD = "DocumentCenterUpload";
    public static final String DOCUMENT_CENTER_EDIT = "DocumentCenterEdit";
    public static final String DOCUMENT_CENTER_DOWNLOAD = "DocumentCenterDownload";
    public static final String DOCUMENT_CENTER_DELETE = "DocumentCenterDelete";
    public static final String WORKBENCH_TODO = "WorkbenchTodo";
    public static final String WORKBENCH_TODO_CALENDAR = "WorkbenchTodoCalendar";
    public static final String WORKBENCH_TODO_CREATE = "WorkbenchTodoCreate";
    public static final String WORKBENCH_TODO_ASSIGN = "WorkbenchTodoAssign";
    public static final String WORKBENCH_TODO_MANAGE = "WorkbenchTodoManage";
    public static final String WORKBENCH_TODO_PUBLIC_DIMENSION_MANAGE = "WorkbenchTodoPublicDimensionManage";
    public static final String WORKBENCH_TODO_FLOW_MANAGE = "WorkbenchTodoFlowManage";
    public static final String AUDIT_VIEW = "AuditView";
    public static final String AUDIT_DETAIL = "AuditDetail";
    public static final String AUDIT_EXPORT = "AuditExport";
    public static final String AUDIT_RETRY = "AuditRetry";

    public static final String DEPARTMENT_VIEW = "DepartmentView";
    public static final String DEPARTMENT_EDIT = "DepartmentEdit";
    public static final String DEPARTMENT_LEADER = "DepartmentLeader";
    public static final String DEPARTMENT_MEMBERS = "DepartmentMembers";

    private PermissionCode() {
    }
}
