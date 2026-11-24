import type { MockResources } from './admin/resources'
import type { MockRoute } from './protocol'
import { MockError } from './protocol.ts'

export const demoUser = {
  id: 1,
  username: 'demo',
  name: '演示用户',
  nameEn: 'Demo User',
}
export const demoRole = {
  id: 1,
  code: 'SUPER_ADMIN',
  name: '演示角色',
  nameEn: 'Demo Role',
}

export function createSession(resources: MockResources) {
  const tokens = new Set<string>()
  let appearance: Record<string, unknown> = {}
  const tokenData = (accessToken: string) => ({ accessToken, passwordChangeRequired: false })
  const routes: MockRoute[] = [
    {
      method: 'GET',
      path: '/auth/login/options',
      access: 'public',
      handle: () => ({ captchaRequired: false, localEnabled: true, domainEnabled: false }),
    },
    {
      method: 'POST',
      path: '/auth/login',
      access: 'public',
      handle: ({ body }) => {
        if (body.username !== demoUser.username || body.password !== 'FlowDemo123' || body.loginMethod !== 'LOCAL')
          throw new MockError(400, '演示账号或密码有误')
        const accessToken = crypto.randomUUID()
        tokens.add(accessToken)
        return tokenData(accessToken)
      },
    },
    {
      method: 'GET',
      path: '/auth/refresh/token',
      handle: ({ token }) => tokenData(token),
    },
    {
      method: 'POST',
      path: '/auth/logout',
      handle: ({ token }) => { tokens.delete(token) },
    },
    {
      method: 'GET',
      path: '/user/detail',
      handle: () => ({
        id: demoUser.id,
        username: demoUser.username,
        nameEn: demoUser.nameEn,
        loginMethod: 'LOCAL',
        profile: {
          nickName: demoUser.name,
          avatar: null,
          gender: null,
          email: null,
          address: null,
        },
        roles: [demoRole],
        currentRole: demoRole,
        appearance,
      }),
    },
    {
      method: 'PATCH',
      path: '/user/profile/appearance',
      handle: ({ body }) => {
        appearance = {
          ...appearance,
          ...body,
        }
      },
    },
    {
      method: 'GET',
      path: '/role/permissions/tree',
      handle: () => resources.tree(),
    },
    {
      method: 'GET',
      path: '/permission/menu/validate',
      handle: ({ query }) => resources.records.rows().some(item => item.path === query.get('path')),
    },
    {
      method: 'GET',
      path: '/in-app-messages/unread-count',
      handle: () => ({ count: 0 }),
    },
    {
      method: 'GET',
      path: '/in-app-messages',
      handle: () => ({ items: [], nextBeforeId: null }),
    },
  ]
  return { routes, tokens }
}
