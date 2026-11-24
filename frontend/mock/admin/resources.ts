import { MockError } from '../protocol.ts'
import { at, bool, collection, ids, number, route, text, unique } from './common.ts'

interface Resource {
  id: number
  parentId: number | null
  code: string
  name: string
  nameEn: string
  type: 'MENU' | 'BUTTON'
  path: string | null
  component: string | null
  icon: string
  layout: string | null
  order: number
  show: boolean
  enable: boolean
  keepAlive: boolean
}

interface ResourceNode extends Resource {
  children: ResourceNode[]
}

function initialResources(): Resource[] {
  const rows: Resource[] = []
  function add(code: string, name: string, nameEn: string, icon: string, order: number, extra: Partial<Resource> = {}) {
    const row: Resource = {
      id: rows.length + 1,
      code,
      name,
      nameEn,
      icon: `i-fe:${icon}`,
      order,
      show: true,
      enable: true,
      keepAlive: false,
      parentId: null,
      type: 'MENU',
      path: null,
      component: null,
      layout: null,
      ...extra,
    }
    rows.push(row)
    return row.id
  }
  function page(parentId: number, code: string, name: string, nameEn: string, icon: string, order: number, path: string, component: string, buttons: string[] = []) {
    const id = add(code, name, nameEn, icon, order, { parentId, path, component: `/src/views/${component}.vue` })
    buttons.forEach((button, index) => add(button, button, '', '', index + 1, { parentId: id, type: 'BUTTON', show: false }))
    return id
  }
  const workbench = add('Workbench', '工作台', 'Workbench', 'grid', 1)
  page(workbench, 'WorkbenchTodo', '待办事项', 'Todo Items', 'list', 1, '/workbench/todos', 'workbench/todos/index', ['WorkbenchTodoAll'])
  page(workbench, 'WorkbenchTodoCalendar', '待办日历', 'Todo Calendar', 'calendar', 2, '/workbench/todo-calendar', 'workbench/todo-calendar/index')
  page(workbench, 'WorkflowMine', '我的流程', 'My Workflows', 'list', 3, '/workbench/workflows', 'workbench/workflow/index')
  page(workbench, 'WorkflowCatalog', '所有流程', 'Workflow Catalog', 'layers', 4, '/workbench/workflow-catalog', 'workbench/workflow/catalog')
  const productCenter = page(workbench, 'ProductCenter', '商品中心', 'Product Center', 'package', 5, '/workbench/products', 'workbench/product-center/index')
  add('ProductCenterDetail', '商品详情', 'Product Details', 'package', 1, {
    parentId: productCenter,
    path: '/workbench/products/:id',
    component: '/src/views/workbench/product-center/detail.vue',
    show: false,
  })
  const system = add('SysMgt', '系统管理', 'System Management', 'settings', 2)
  page(system, 'Resource_Mgt', '资源管理', 'Resource Management', 'list', 1, '/pms/resource', 'pms/resource/index')
  const role = page(system, 'RoleMgt', '角色管理', 'Role Management', 'user-check', 2, '/pms/role', 'pms/role/index')
  add('RoleUser', '分配用户', 'Assign Users', 'user-plus', 1, {
    parentId: role,
    path: '/pms/role/user/:roleId',
    component: '/src/views/pms/role/role-user.vue',
    show: false,
  })
  page(system, 'UserMgt', '用户管理', 'User Management', 'user', 3, '/pms/user', 'pms/user/index', ['AddUser'])
  page(system, 'ParamMgt', '参数管理', 'Parameter Management', 'sliders', 4, '/pms/param', 'pms/param/index', [
    'ParamCreate',
    'ParamEdit',
    'ParamDelete',
    'ParamSort',
    'ParamRefresh',
  ])
  page(system, 'AuditView', '操作日志', 'Operation Audit', 'clipboard', 5, '/pms/audit', 'pms/audit/index', ['AuditDetail', 'AuditExport', 'AuditRetry'])
  page(system, 'AuthPolicyMgt', '认证安全策略', 'Authentication Policy', 'shield', 6, '/pms/auth-policy', 'pms/auth-policy/index')
  page(system, 'DepartmentView', '组织架构', 'Organization', 'users', 7, '/pms/department', 'pms/department/index', ['DepartmentEdit', 'DepartmentMembers', 'DepartmentLeader'])
  page(system, 'FieldMgt', '字段管理', 'Field Management', 'list', 10, '/pms/field', 'pms/field/index', ['FieldCreate', 'FieldEdit', 'FieldDelete'])
  page(system, 'MailMgt', '邮件管理', 'Mail Management', 'mail', 12, '/pms/mail', 'pms/mail/index', [
    'MailAccount',
    'MailTemplate',
    'MailLog',
    'MailSend',
  ])
  page(system, 'NotificationGroupView', '通知群组', 'Notification Groups', 'users', 13, '/pms/notification-group', 'pms/notification-group/index', [
    'NotificationGroupEdit',
    'NotificationSceneRecipientEdit',
    'WorkflowNotificationSceneEdit',
    'WorkflowNotificationDeliveryView',
  ])
  page(system, 'OpenApiView', '开放 API', 'Open API', 'key', 14, '/pms/open-api', 'pms/open-api/index', [
    'OpenApiAppManage',
    'OpenApiEndpointPublish',
    'OpenApiGrantManage',
    'OpenApiSecretManage',
    'OpenApiCallLogView',
  ])
  page(system, 'WorkflowTemplateMgt', '流程模板', 'Workflow Templates', 'git-branch', 19, '/pms/workflow', 'pms/workflow/index')
  return rows
}

export function createResources() {
  const records = collection(initialResources())
  function children(parentId: number | null) {
    return records.rows().filter(row => row.parentId === parentId).sort((a, b) => a.order - b.order || a.id - b.id)
  }
  function tree(parentId: number | null = null): ResourceNode[] {
    return children(parentId).map(row => ({
      ...row,
      children: tree(row.id),
    }))
  }
  function save(body: Record<string, unknown>, current?: Resource) {
    const code = text(body.code, current?.code)
    unique(records.rows().filter(row => row !== current), code, row => row.code)
    const parentId = body.parentId === null
      ? null
      : number(body.parentId, current?.parentId ?? 0) || null
    let ancestor = parentId
    while (ancestor !== null) {
      if (ancestor === current?.id)
        throw new MockError(400, '不能将菜单移动到自身或其子菜单')
      ancestor = records.get(ancestor).parentId
    }
    const update = {
      code,
      parentId,
      name: text(body.name, current?.name),
      nameEn: text(body.nameEn, current?.nameEn),
      type: body.type === 'BUTTON' || (body.type === undefined && current?.type === 'BUTTON')
        ? 'BUTTON' as const
        : 'MENU' as const,
      path: body.path === null
        ? null
        : text(body.path, current?.path ?? '') || null,
      component: body.component === null
        ? null
        : text(body.component, current?.component ?? '') || null,
      layout: body.layout === null
        ? null
        : text(body.layout, current?.layout ?? '') || null,
      icon: text(body.icon, current?.icon),
      show: bool(body.show, current?.show ?? true),
      enable: bool(body.enable, current?.enable ?? true),
      keepAlive: bool(body.keepAlive, current?.keepAlive ?? false),
      order: number(body.order, current?.order ?? children(parentId).length + 1),
    }
    return current
      ? Object.assign(current, update)
      : records.create(update)
  }
  return {
    tree,
    records,
    routes: [
      route('GET', '/permission/tree', () => tree()),
      route('GET', at('/permission/button/:id'), ({ params }) => {
        const pageData = children(Number(params[1])).filter(row => row.type === 'BUTTON')
        return { pageData, total: pageData.length }
      }),
      route('POST', '/permission', ({ body }) => save(body)),
      route('PATCH', at('/permission/:id'), ({ params, body }) => save(body, records.get(params[1]))),
      route('PATCH', '/permission/sort', ({ body }) => {
        const parentId = body.parentId ?? null
        const rows = ids(body.ids).map(id => records.get(id))
        if (rows.some(row => row.parentId !== parentId))
          throw new MockError(400, '资源须属于同一菜单')
        rows.forEach((row, index) => {
          row.order = index + 1
        })
      }),
      route('DELETE', at('/permission/:id'), ({ params }) => {
        const row = records.get(params[1])
        if (children(row.id).length)
          throw new MockError(409, '请先删除子资源')
        records.remove(row.id)
      }),
    ],
  }
}

export type MockResources = ReturnType<typeof createResources>
