import type { MockDirectory, MockUser } from './directory'
import { mockTime } from '../fixtures.ts'
import { MockError, workflowPage } from '../protocol.ts'
import { at, bool, enabled, ids, matchesAny, route, text, unique } from './common.ts'

export function userRoutes(directory: MockDirectory) {
  const { users, roles, membershipWrite, present, clearLeadership } = directory
  function roleIds(value: unknown) {
    const selected = ids(value)
    selected.forEach(id => roles.get(id))
    return selected
  }
  function temporaryCredential(user: MockUser) {
    return {
      userId: user.id,
      username: user.username,
      temporaryPassword: `Demo-${crypto.randomUUID().slice(0, 8)}`,
      expiresAt: new Date(Date.now() + 86_400_000).toISOString(),
    }
  }
  return [
    route('GET', '/user', ({ query }) => {
      const role = Number(query.get('roleId'))
      const department = Number(query.get('departmentId'))
      const assigned = query.get('roleAssigned') ?? query.get('assigned')
      const rows = users.rows().filter(user =>
        matchesAny(query.get('username'), user.username)
        && matchesAny(query.get('keyword'), user.username, user.nameCn, user.nameEn)
        && enabled(user.enable, query.get('enable'))
        && (!query.get('gender') || String(user.gender) === query.get('gender'))
        && (!role || assigned === null || user.roleIds.includes(role) === (assigned === 'true' || assigned === '1'))
        && (query.get('unassigned') !== 'true' || !user.departmentIds.length)
        && (!department || (query.get('departmentScope') === 'PRIMARY'
          ? user.departmentId === department
          : user.departmentIds.includes(department))),
      )
      return workflowPage(rows.map(present), query)
    }),
    route('POST', '/user', ({ body }) => {
      const username = text(body.username)
      unique(users.rows(), username, user => user.username)
      const time = mockTime()
      const assignedRoles = roleIds(body.roleIds)
      const membership = body.memberships
        ? membershipWrite(body.memberships)
        : { departmentIds: [], departmentId: null, departmentVersion: 1 }
      const user = users.create({
        username,
        nameCn: text(body.nameCn),
        nameEn: text(body.nameEn),
        domainAccount: text(body.domainAccount),
        mobile: text(body.mobile),
        enable: bool(body.enable, true),
        gender: null,
        avatar: null,
        createTime: time,
        updateTime: time,
        roleIds: assignedRoles,
        loginMethod: 'LOCAL',
        ...membership,
      })
      return temporaryCredential(user)
    }),
    route('PATCH', at('/user/:id'), ({ params, body }) => {
      const user = users.get(params[1])
      if (user.id === 1 && body.enable === false)
        throw new MockError(409, '无法停用当前演示账号')
      const membership = body.memberships
        ? membershipWrite(body.memberships, user)
        : {}
      const nextRoles = Array.isArray(body.roleIds)
        ? roleIds(body.roleIds)
        : user.roleIds
      Object.assign(user, {
        nameCn: text(body.nameCn, user.nameCn),
        nameEn: text(body.nameEn, user.nameEn),
        mobile: text(body.mobile, user.mobile),
        domainAccount: text(body.domainAccount, user.domainAccount),
        enable: bool(body.enable, user.enable),
        roleIds: nextRoles,
        updateTime: mockTime(),
        ...membership,
      })
      if (!user.enable)
        clearLeadership(user.id)
      return present(user)
    }),
    route('DELETE', at('/user/:id'), ({ params }) => {
      const user = users.get(params[1])
      if (user.id === 1)
        throw new MockError(409, '无法删除当前演示账号')
      clearLeadership(user.id)
      users.remove(user.id)
    }),
    route('PATCH', at('/user/password/reset/:id'), ({ params }) => temporaryCredential(users.get(params[1]))),
  ]
}
