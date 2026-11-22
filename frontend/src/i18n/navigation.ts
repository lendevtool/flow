import { t } from './index'
import { displayName } from './names'

const names: Readonly<Partial<Record<string, string>>> = {
  Login: '登录页',
  Home: '首页',
  403: '没有权限',
  404: '页面飞走了',
  SysMgt: '系统管理',
  BusinessInfo: '业务信息',
  Resource_Mgt: '资源管理',
  RoleMgt: '角色管理',
  UserMgt: '用户管理',
  RoleUser: '分配用户',
  RoleUserTransfer: '穿梭分配用户',
  UserProfile: '个人资料',
  ParamMgt: '参数管理',
  FieldMgt: '字段管理',
  AuditView: '操作日志',
  NotificationGroupView: '通知群组',
  NotificationGroupEdit: '维护通知群组',
  NotificationSceneRecipientEdit: '维护场景绑定',
  WorkflowNotificationSceneEdit: '维护流程通知场景',
  WorkflowNotificationDeliveryView: '查询流程通知投递',
  DepartmentView: '组织架构',
  TradingCalendarMgt: '交易日历',
  DocumentCenterView: '文档中心',
  DocumentGenerationView: '文档生成',
  DocumentGenerationRun: '生成文档',
  DocumentGenerationReadFile: '预览和下载',
  DocumentGenerationTemplateManage: '管理模板',
  DocumentGenerationTemplatePublish: '发布模板',
  InstitutionView: '机构管理',
  Workbench: '工作台',
  WorkbenchTodo: '待办事项',
  WorkbenchTodoCalendar: '待办日历',
  AuthPolicyMgt: '认证安全策略',
  Demo: '业务示例',
  ImgUpload: '图片上传',
  DemoTranslate: 'AI 翻译',
  Base: '基础功能',
  BaseComponents: '基础组件',
  Icon: '图标 Icon',
  ExternalLink: '外链(可内嵌打开)',
  MyBlog: '掘金',
  SUPER_ADMIN: '超级管理员',
  ROLE_QA: '质检员',
}

/** 优先使用已填写的英文名；预置项保留内置译名作为后备。 */
export function navigationLabel(code: unknown, name: unknown, nameEn?: unknown): string {
  const fallback = typeof name === 'string' && typeof code === 'string' && Object.hasOwn(names, code) && names[code] === name
    ? t(name)
    : name
  return displayName(fallback, nameEn)
}

export function resourceLabel(item: { code?: unknown, name?: unknown, nameEn?: unknown }): string {
  return navigationLabel(item.code, item.name, item.nameEn)
}
