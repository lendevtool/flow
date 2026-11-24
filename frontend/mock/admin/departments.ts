import type { Department } from '../../src/views/pms/department/api'
import type { MockDirectory } from './directory'
import { MockError, object, workflowPage } from '../protocol.ts'
import { at, bool, enabled, matchesAny, number, route, text } from './common.ts'

export function departmentRoutes(directory: MockDirectory) {
  const { departments, users, present, departmentRef, memberships } = directory
  function form(body: Record<string, unknown>, current?: Department) {
    const id = body.parentId === null
      ? null
      : number(body.parentId, current?.parentId ?? 0) || null
    for (let parentId = id; parentId !== null; parentId = departments.get(parentId).parentId) {
      if (current?.id === parentId)
        throw new MockError(400, '上级部门不能为自身或下级部门')
    }
    const name = text(body.name, current?.name).trim()
    if (!name)
      throw new MockError(400, '请输入部门名称')
    return {
      parentId: id,
      name,
      sortOrder: number(body.sortOrder, current?.sortOrder ?? 0),
      enabled: bool(body.enabled, current?.enabled ?? true),
    }
  }
  function members(query: URLSearchParams, candidate: boolean) {
    const departmentId = Number(query.get('departmentId')) || null
    const included = new Set([departmentId])
    if (departmentId && query.get('includeDescendants') === 'true') {
      for (const parentId of included) {
        for (const row of departments.rows()) {
          if (row.parentId === parentId)
            included.add(row.id)
        }
      }
    }
    const rows = users.rows().filter(user =>
      matchesAny(query.get('keyword'), user.username, user.nameCn, user.nameEn)
      && enabled(user.enable, query.get('enable'))
      && (query.get('unassigned') !== 'true' || !user.departmentIds.length)
      && (candidate || !departmentId || user.departmentIds.some(id => included.has(id))),
    ).map(user => ({
      ...present(user),
      enabled: user.enable,
      matchedDepartments: user.departmentIds.filter(id => !departmentId || included.has(id)).map(departmentRef),
    }))
    return workflowPage(rows, query)
  }
  return [
    route('GET', '/department', () => departments.rows()),
    route('GET', '/department/members', ({ query }) => members(query, false)),
    route('GET', '/department/users', ({ query }) => members(query, true)),
    route('GET', at('/department/users/:id/memberships'), ({ params }) => memberships(users.get(params[1]))),
    route('GET', at('/user/:id/person'), ({ params }) => {
      const user = users.get(params[1])
      return {
        ...present(user),
        email: null,
        primaryDepartmentId: user.departmentId,
      }
    }),
    route('POST', '/department', ({ body }) => departments.create({
      ...form(body),
      leaderUserId: null,
      leaderName: null,
    })),
    route('PUT', at('/department/:id'), ({ params, body }) => {
      const row = departments.get(params[1])
      return Object.assign(row, form(body, row))
    }),
    route('PUT', at('/department/:id/leader'), ({ params, body }) => {
      const row = departments.get(params[1])
      const id = number(body.userId, 0) || null
      const user = id === null
        ? null
        : users.get(id)
      if (user && (!user.enable || !user.departmentIds.includes(row.id)))
        throw new MockError(400, '请选择当前部门已启用的成员')
      row.leaderUserId = id
      row.leaderName = user
        ? user.nameCn || user.username
        : null
      return row
    }),
    route('POST', '/department/member-changes', ({ body }) => {
      if (!Array.isArray(body.users) || !['ADD', 'REMOVE', 'TRANSFER'].includes(text(body.action)))
        throw new MockError(400, '请选择成员和操作类型')
      const source = body.action === 'ADD'
        ? null
        : departments.get(number(body.sourceDepartmentId, 0))
      const target = body.action === 'REMOVE'
        ? null
        : departments.get(number(body.targetDepartmentId, 0))
      if (target && (!target.enabled || target.id === source?.id))
        throw new MockError(400, '目标部门无效')
      const updates = body.users.map((value) => {
        const input = object(value)
        const user = users.get(number(input.userId, 0))
        if (user.departmentVersion !== input.expectedVersion || (source && !user.departmentIds.includes(source.id)))
          throw new MockError(409, '成员归属已变化')
        const departmentIds = user.departmentIds.filter(id => id !== source?.id)
        if (target && !departmentIds.includes(target.id))
          departmentIds.push(target.id)
        const departmentId = departmentIds.includes(user.departmentId ?? 0)
          ? user.departmentId
          : target?.id ?? departmentIds[0] ?? null
        return { user, departmentIds, departmentId }
      })
      for (const { user, departmentIds, departmentId } of updates) {
        Object.assign(user, { departmentIds, departmentId, departmentVersion: user.departmentVersion + 1 })
        if (source?.leaderUserId === user.id) {
          source.leaderUserId = null
          source.leaderName = null
        }
      }
      return updates.map(({ user }) => memberships(user))
    }),
  ]
}
