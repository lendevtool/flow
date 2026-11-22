// @vitest-environment jsdom

import { expect, it, vi } from 'vitest'
import { createApp } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { setLanguage } from '@/i18n'
import { setupStore } from '@/store'
import { request } from '@/utils'
import RoleUserTransfer from '@/views/pms/role/RoleUserTransfer.vue'

vi.mock('@/views/pms/role/WorkflowHandlers.vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      emits: ['saved', 'close'],
      setup(_, { emit }) {
        return () => h('button', {
          onClick: () => {
            emit('saved')
            emit('close')
          },
        }, '保存主备')
      },
    }),
  }
})

it('shows workflow handlers only on assigned users and refreshes the missing-primary warning after save', async () => {
  setLanguage('zh-CN')
  let primaryUserId: number | null = null
  const assigned = [
    { id: 11, username: 'primary-fixture', nameCn: '审核人' },
    { id: 12, username: 'backup-fixture', nameCn: '备办人' },
    { id: 13, username: 'member-fixture', nameCn: '普通成员' },
  ]
  const get = vi.spyOn(request, 'get').mockImplementation(async (url, config) => {
    if (url === '/workflow/roles/7/handlers/summary') {
      return {
        data: {
          primaryUserId,
          backupUserIds: [12],
          primaryEnabled: primaryUserId !== null,
          requiresPrimaryHandler: true,
        },
      }
    }
    if (url === '/workflow/roles/8/handlers/summary') {
      return {
        data: {
          primaryUserId: null,
          backupUserIds: [],
          primaryEnabled: false,
          requiresPrimaryHandler: false,
        },
      }
    }
    if (url === '/user') {
      return {
        data: {
          pageData: config?.params.roleAssigned
            ? assigned
            : [],
          total: config?.params.roleAssigned ? assigned.length : 0,
        },
      }
    }
    throw new Error(`Unexpected GET ${url}`)
  })
  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp(RoleUserTransfer)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/roles/:roleId/users', name: 'RoleUserTransfer', component: RoleUserTransfer },
    ],
  })
  setupStore(app)
  app.use(router)
  await router.push('/roles/7/users')
  app.mount(root)

  const assignedSide = () => root.querySelector('section[aria-label="已分配用户"]')
  const availableSide = () => root.querySelector('section[aria-label="未分配用户"]')
  const assignment = (user: string) => Array.from(assignedSide()?.querySelectorAll('tr') ?? [])
    .find(row => row.textContent?.includes(user))
    ?.lastElementChild
    ?.textContent
    ?.trim()
  const findButton = (name: string) => Array.from(root.querySelectorAll('button'))
    .find(button => button.textContent?.trim() === name)

  try {
    await vi.waitFor(() => expect(findButton('配置主办')).toBeDefined())
    expect(assignedSide()?.querySelector('thead')?.textContent).toContain('主备')
    expect(availableSide()?.querySelector('thead')?.textContent).not.toContain('主备')
    expect(assignment('backup-fixture')).toBe('备办')
    expect(assignment('member-fixture')).toBe('—')

    findButton('配置主办')?.click()
    await vi.waitFor(() => expect(findButton('保存主备')).toBeDefined())
    primaryUserId = 11
    findButton('保存主备')?.click()
    await vi.waitFor(() => expect(assignment('primary-fixture')).toBe('主办'))
    expect(findButton('配置主办')).toBeUndefined()
    expect(assignment('backup-fixture')).toBe('备办')

    await router.push('/roles/8/users')
    await vi.waitFor(() => expect(assignment('primary-fixture')).toBe('—'))
    expect(findButton('配置主办')).toBeUndefined()
  }
  finally {
    app.unmount()
    root.remove()
    get.mockRestore()
  }
})
