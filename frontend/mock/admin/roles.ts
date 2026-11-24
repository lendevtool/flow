import type { MockDirectory } from './directory'
import { MockError, workflowPage } from '../protocol.ts'
import { at, bool, enabled, ids, matchesAny, number, route, text, unique } from './common.ts'

export function roleRoutes({ roles, users }: MockDirectory) {
  const list = (query: URLSearchParams) => roles.rows().filter(row =>
    matchesAny(query.get('name'), row.name, row.nameEn) && enabled(row.enable, query.get('enable')),
  )
  function handlers(id: string) {
    const role = roles.get(id)
    const members = users.rows().filter(user => user.roleIds.includes(role.id)).map(user =>
      ({ id: user.id, name: user.nameCn || user.username, enabled: user.enable }),
    )
    return { members, primaryUserId: role.primaryUserId, backupUserIds: role.backupUserIds }
  }
  return [
    route('GET', '/role', ({ query }) => list(query)),
    route('GET', '/role/page', ({ query }) => workflowPage(list(query), query)),
    route('POST', '/role', ({ body }) => {
      const code = text(body.code)
      unique(roles.rows(), code, row => row.code)
      return roles.create({
        code,
        name: text(body.name),
        nameEn: text(body.nameEn),
        enable: bool(body.enable, true),
        permissionIds: ids(body.permissionIds),
        primaryUserId: null,
        backupUserIds: [],
      })
    }),
    route('PATCH', at('/role/:id'), ({ params, body }) => {
      const role = roles.get(params[1])
      role.name = text(body.name, role.name)
      role.nameEn = text(body.nameEn, role.nameEn)
      role.enable = bool(body.enable, role.enable)
      if (Array.isArray(body.permissionIds))
        role.permissionIds = ids(body.permissionIds)
      return role
    }),
    route('DELETE', at('/role/:id'), ({ params }) => {
      const role = roles.get(params[1])
      if (role.code === 'SUPER_ADMIN')
        throw new MockError(409, '无法删除演示管理员角色')
      for (const user of users.rows())
        user.roleIds = user.roleIds.filter(id => id !== role.id)
      roles.remove(role.id)
    }),
    route('PATCH', /^\/role\/users\/(add|remove)\/(\d+)$/, ({ params, body }) => {
      const role = roles.get(params[2])
      const targets = ids(body.userIds).map(users.get)
      for (const user of targets) {
        if (params[1] === 'add') {
          user.roleIds = [
            ...new Set([...user.roleIds, role.id]),
          ]
        }
        else {
          user.roleIds = user.roleIds.filter(id => id !== role.id)
          if (role.primaryUserId === user.id)
            role.primaryUserId = null
          role.backupUserIds = role.backupUserIds.filter(id => id !== user.id)
        }
      }
    }),
    route('GET', at('/workflow/roles/:id/handlers'), ({ params }) => handlers(params[1])),
    route('GET', at('/workflow/roles/:id/handlers/summary'), ({ params }) => {
      const { members, ...values } = handlers(params[1])
      return {
        ...values,
        primaryEnabled: members.some(user => user.id === values.primaryUserId && user.enabled),
        requiresPrimaryHandler: true,
      }
    }),
    route('PUT', at('/workflow/roles/:id/handlers'), ({ params, body }) => {
      const role = roles.get(params[1])
      const { members } = handlers(params[1])
      const primary = number(body.primaryUserId, 0) || null
      const backups = ids(body.backupUserIds)
      if ([primary, ...backups].some(id => id !== null && !members.some(user => id === user.id && user.enabled)))
        throw new MockError(400, '请选择已启用的角色成员')
      role.primaryUserId = primary
      role.backupUserIds = backups.filter(id => id !== primary)
      return handlers(params[1])
    }),
  ]
}
