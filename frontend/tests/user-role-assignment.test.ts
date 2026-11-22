// @vitest-environment jsdom

import type { App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import { createApp } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { setLanguage } from '@/i18n'
import { setupStore } from '@/store'
import { request } from '@/utils'
import UserManagement from '@/views/pms/user/index.vue'

let app: App | undefined
let root: HTMLDivElement | undefined

afterEach(() => {
  app?.unmount()
  root?.remove()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function button(name: string): HTMLButtonElement {
  const element = [
    ...document.querySelectorAll('button'),
  ].find(button => button.textContent?.trim() === name)
  if (!element)
    throw new Error(`Button "${name}" was not rendered`)
  return element
}

function roleCheckbox(group: string, name: string): HTMLElement {
  const checkboxes = document.querySelectorAll<HTMLElement>(`[role="group"][aria-label="${group}"] [role="checkbox"]`)
  const checkbox = Array.from(checkboxes).find(element => element.textContent?.trim() === name)
  if (!checkbox)
    throw new Error(`Role "${name}" was not rendered in "${group}"`)
  return checkbox
}

it('saves the final role list and refreshes the user row after a transfer', async () => {
  setLanguage('en-US')
  const previousRole = { id: 7, name: 'Legacy reviewer', enable: false }
  const newRole = { id: 12, name: 'Document reviewer', enable: true }
  const user = {
    id: 33,
    username: 'fixture-user',
    roles: [previousRole],
    departments: [],
  }
  vi.spyOn(request, 'get').mockImplementation(async (url) => {
    if (url === '/role?enable=1') {
      return {
        data: [newRole],
      }
    }
    if (url === '/user') {
      return {
        data: {
          pageData: [
            structuredClone(user),
          ],
          total: 1,
        },
      }
    }
    throw new Error(`Unexpected GET ${url}`)
  })
  const patch = vi.spyOn(request, 'patch').mockImplementation(async () => {
    user.roles = [newRole]
    return { data: null }
  })
  vi.stubGlobal('$message', {
    success: vi.fn(),
  })

  root = document.createElement('div')
  document.body.append(root)
  app = createApp(UserManagement)
  setupStore(app)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'UserMgt', component: UserManagement },
    ],
  })
  app.use(router)
  await router.push('/')
  app.directive('permission', {})
  app.mount(root)

  await vi.waitFor(() => button('Assign roles'))
  button('Assign roles').click()
  await vi.waitFor(() => expect(roleCheckbox('Available roles', 'Document reviewer').getAttribute('aria-disabled')).not.toBe('true'))
  roleCheckbox('Assigned roles', 'Legacy reviewer (Disabled)').click()
  await vi.waitFor(() => expect(button('← Remove').disabled).toBe(false))
  button('← Remove').click()
  await vi.waitFor(() => roleCheckbox('Available roles', 'Legacy reviewer (Disabled)'))
  roleCheckbox('Available roles', 'Document reviewer').click()
  await vi.waitFor(() => expect(button('Add →').disabled).toBe(false))
  button('Add →').click()
  await vi.waitFor(() => roleCheckbox('Assigned roles', 'Document reviewer'))
  expect(patch).not.toHaveBeenCalled()

  button('OK').click()
  await vi.waitFor(() => expect(patch).toHaveBeenCalledExactlyOnceWith('/user/33', {
    id: 33,
    roleIds: [12],
  }))
  await vi.waitFor(() => expect(root?.textContent).toContain('Document reviewer'))
  expect(root?.textContent).not.toContain('Legacy reviewer')
})
