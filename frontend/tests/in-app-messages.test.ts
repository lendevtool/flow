// @vitest-environment jsdom

import { expect, it, vi } from 'vitest'
import { createApp, h } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import api from '@/api/in-app-messages/api'
import NotificationBell from '@/layouts/components/NotificationBell.vue'
import { setupStore, useUserStore } from '@/store'

it('refreshes the badge and removes the message when it is marked read under the unread filter', async () => {
  const message = {
    id: 9,
    messageType: 'TASK_ASSIGNED',
    title: 'Review requested',
    summary: 'A workflow task is ready.',
    createdAt: '2026-11-18T00:00:00Z',
    readAt: null,
    targetUrl: '/workbench/todos?todo=35',
  }
  let unread = true
  const count = vi.spyOn(api, 'unreadCount').mockImplementation(async () => ({
    data: {
      count: Number(unread),
    },
  }))
  const list = vi.spyOn(api, 'list').mockImplementation(async query => ({
    data: {
      items: query.unread === undefined || query.unread === unread
        ? [
            {
              ...message,
              readAt: unread ? null : '2026-11-18T00:01:00Z',
            },
          ]
        : [],
      nextBeforeId: null,
    },
  }))
  const mark = vi.spyOn(api, 'markRead').mockImplementation(async (id) => {
    expect(id).toBe(9)
    unread = false
  })
  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp({
    render: () => h(NotificationBell),
  })
  setupStore(app)
  useUserStore().setUser({ id: 4 })
  const emptyPage = {
    render: () => null,
  }
  app.use(createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: emptyPage },
      { path: '/workbench/todos', component: emptyPage },
    ],
  }))
  app.mount(root)
  const button = (title: string) => Array.from(document.querySelectorAll('button'))
    .find(button => button.textContent?.trim() === title)
  try {
    await vi.waitFor(() => expect(root.querySelector('button')?.getAttribute('aria-label')).toBe('站内消息，1 条未读'))
    root.querySelector('button')?.click()
    await vi.waitFor(() => expect(document.body.textContent).toContain('Review requested'))
    document.querySelector<HTMLElement>('[role="checkbox"]')?.click()
    await vi.waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ unread: true }), expect.any(AbortSignal)))
    await vi.waitFor(() => expect(button('标记已读')).toBeDefined())
    button('标记已读')?.click()
    await vi.waitFor(() => expect(document.body.textContent).toContain('没有未读消息'))
    expect(root.querySelector('button')?.getAttribute('aria-label')).toBe('站内消息')
    expect(document.body.textContent).not.toContain('Review requested')
  }
  finally {
    app.unmount()
    root.remove()
    count.mockRestore()
    list.mockRestore()
    mark.mockRestore()
  }
})
