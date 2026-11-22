import axios from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { usePermissionStore } from '../src/store/modules/permission'
import { useTabStore } from '../src/store/modules/tab'
import { setupInterceptors } from '../src/utils/http/interceptors'

const { push, resolveResError } = vi.hoisted(() => ({
  push: vi.fn(),
  resolveResError: vi.fn(),
}))
vi.mock('../src/store/modules/router', () => ({
  useRouterStore: () => ({
    router: { push },
  }),
}))
vi.mock('@/utils', () => import('../src/utils/is.js'))
vi.mock('@/store', () => ({
  useAuthStore: () => ({ accessToken: 'test-token' }),
}))
vi.mock('../src/utils/http/helpers', async (importOriginal) => {
  const helpers = await importOriginal()
  resolveResError.mockImplementation(helpers.resolveResError)
  return { resolveResError }
})

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

describe('tab actions', () => {
  it('keeps the active page when only tabs to its right are closed', () => {
    const store = useTabStore()
    store.setTabs(['/a', '/b', '/c'].map(path => ({ path })))
    store.activeTab = '/a'
    store.removeRight('/b')
    expect(store.tabs.map(tab => tab.path)).toEqual(['/a', '/b'])
    expect(push).not.toHaveBeenCalled()
  })

  it('navigates to a retained page when the active tab is removed', () => {
    const store = useTabStore()
    store.setTabs(['/a', '/b', '/c'].map(path => ({ path })))
    store.activeTab = '/c'
    store.removeRight('/b')
    expect(push).toHaveBeenCalledWith('/b')
  })

  it.each(['removeLeft', 'removeRight', 'removeOther'])('ignores a stale target for %s', (action) => {
    const store = useTabStore()
    store.setTabs([
      { path: '/a' },
    ])
    store.activeTab = '/a'
    expect(() => store[action]('/missing')).not.toThrow()
    expect(store.tabs).toEqual([
      { path: '/a' },
    ])
    expect(push).not.toHaveBeenCalled()
  })

  it('handles closing the last tab without reading an undefined path', async () => {
    const store = useTabStore()
    store.setTabs([
      { path: '/a' },
    ])
    store.activeTab = '/a'
    await expect(store.removeTab('/a')).resolves.toBeUndefined()
    expect(push).toHaveBeenCalledWith('/')
  })
})

describe('permission rebuilding', () => {
  it('does not duplicate routes or mutate external menu paths', () => {
    const store = usePermissionStore()
    const permissions = [
      {
        code: 'Docs',
        name: 'Docs',
        type: 'MENU',
        path: 'https://example.com',
        show: true,
        enable: true,
      },
    ]
    store.setPermissions(permissions)
    store.setPermissions(permissions)
    expect(permissions[0].path).toBe('https://example.com')
    expect(store.accessRoutes).toHaveLength(1)
    expect(store.accessRoutes[0].meta.originPath).toBe('https://example.com')
  })

  it('registers only local paths and web frames, with contact links reserved for external opening', () => {
    const store = usePermissionStore()
    const menu = (code, path) => ({
      code,
      name: code,
      type: 'MENU',
      path,
      show: true,
      enable: true,
    })
    store.setPermissions([
      menu('Docs', 'https://example.test/docs'),
      menu('Support', 'mailto:team@example.test'),
      menu('OtherScheme', 'ftp://example.test/docs/mailto:notes'),
      menu('OtherOrigin', '//example.test/docs'),
      {
        ...menu('Local', '/local'),
        redirect: '//example.test/docs',
      },
    ])

    expect(store.accessRoutes.map(route => route.path)).toEqual(['/iframe/docs', '/local'])
    expect(store.menus.find(item => item.key === 'Support')).toMatchObject({ originPath: 'mailto:team@example.test' })
    expect(store.menus.find(item => item.key === 'OtherScheme').originPath).toBeUndefined()
    expect(store.accessRoutes.find(route => route.name === 'Local').redirect).toBeUndefined()
  })
})

describe('request failures', () => {
  it.each([
    [
      { code: 500, data: null, message: '流程审计配置异常，本次操作未提交。请求编号：test' },
      '流程审计配置异常，本次操作未提交。请求编号：test',
    ],
    [
      { status: 500, message: 'Internal exception details' },
      '服务器发生异常',
    ],
  ])('displays supported 500 messages and hides fallback exception details', async (data, message) => {
    const client = axios.create({
      adapter: config => Promise.reject(new axios.AxiosError('HTTP 500', 'ERR_BAD_RESPONSE', config, undefined, {
        status: 500,
        config,
        headers: { 'content-type': 'application/json' },
        data,
      })),
    })
    setupInterceptors(client)
    await expect(client.post('/workflow/definitions', {}, { needTip: false })).rejects.toMatchObject({ code: 500, message })
  })

  it('honors needTip for network errors without a response', async () => {
    const client = axios.create({
      adapter: config => Promise.reject(new axios.AxiosError('offline', 'ERR_NETWORK', config)),
    })
    setupInterceptors(client)
    await expect(client.get('/test', { needTip: false })).rejects.toMatchObject({ code: 'ERR_NETWORK' })
    expect(resolveResError).toHaveBeenCalledWith('ERR_NETWORK', 'offline', false)
  })

  it('preserves cancellation and does not display an error for it', async () => {
    const controller = new AbortController()
    controller.abort()
    const client = axios.create()
    setupInterceptors(client)
    const error = await client.get('/test', { signal: controller.signal }).catch(error => error)
    expect(axios.isCancel(error)).toBe(true)
    expect(resolveResError).not.toHaveBeenCalled()
  })
})
