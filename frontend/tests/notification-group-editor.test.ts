// @vitest-environment jsdom

import { expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { setupStore } from '@/store'
import api from '@/views/pms/notification-group/api'
import GroupEditor from '@/views/pms/notification-group/GroupEditor.vue'

it('keeps the edited name and prevents stale resubmission after a version conflict', async () => {
  const group = vi.spyOn(api, 'group').mockResolvedValue({
    code: 0,
    message: '',
    data: {
      id: 4,
      name: 'Saved name',
      description: null,
      enabled: true,
      version: 2,
      users: [
        {
          userId: 1,
          nameCn: '测试',
          nameEn: 'Test',
          username: 'test',
          status: 'ACTIVE',
        },
      ],
      departments: [],
    },
  })
  const usages = vi.spyOn(api, 'usages').mockResolvedValue({
    code: 0,
    message: '',
    data: { pageData: [], total: 0 },
  })
  const update = vi.spyOn(api, 'update').mockImplementation(async (id, draft) => {
    expect(id).toBe(4)
    expect(draft).toEqual({
      name: 'Edited name',
      description: null,
      userIds: [1],
      departments: [],
      expectedVersion: 2,
    })
    throw Object.assign(new Error('Conflict'), {
      code: 409,
      error: {
        data: { reason: 'VERSION_CONFLICT' },
      },
    })
  })
  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp({
    render: () => h(GroupEditor, { id: 4, readonly: false, canAudit: false }),
  })
  setupStore(app)
  app.mount(root)

  const nameInput = () => document.querySelector<HTMLInputElement>('input[aria-label="群组名称"]')
  const save = () => [
    ...document.querySelectorAll('button'),
  ].find(button => button.textContent?.trim() === '保存')
  try {
    await vi.waitFor(() => expect(nameInput()?.value).toBe('Saved name'))
    const input = nameInput()!
    input.value = 'Edited name'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    save()?.click()

    await vi.waitFor(() => expect(document.body.textContent).toContain('当前草稿已保留'))
    expect(nameInput()?.value).toBe('Edited name')
    expect(save()?.disabled).toBe(true)
    expect(update).toHaveBeenCalledTimes(1)
  }
  finally {
    app.unmount()
    root.remove()
    group.mockRestore()
    usages.mockRestore()
    update.mockRestore()
  }
})
