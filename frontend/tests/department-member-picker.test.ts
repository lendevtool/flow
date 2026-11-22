// @vitest-environment jsdom

import type { Member } from '@/views/pms/department/api'
import { expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref } from 'vue'
import { setupStore } from '@/store'
import api from '@/views/pms/department/api'
import MemberPicker from '@/views/pms/department/MemberPicker.vue'

function member(id: number, username: string, departmentId: number): Member {
  return {
    id,
    username,
    nameCn: username,
    nameEn: null,
    departmentId,
    departmentName: `部门 ${departmentId}`,
    departmentVersion: 1,
    departments: [
      { id: departmentId, name: `部门 ${departmentId}` },
    ],
    matchedDepartments: [],
    enabled: true,
  }
}

it('opens with local active members, switches to company users and resets on reopening', async () => {
  const local = member(1, '本部门用户甲', 7)
  const other = member(2, '其他部门用户乙', 8)
  const members = vi.spyOn(api, 'members').mockImplementation(async ({ departmentId, enable }) => {
    expect({ departmentId, enable }).toEqual({ departmentId: 7, enable: true })
    return {
      data: {
        pageData: [local],
        total: 1,
      },
    }
  })
  const candidates = vi.spyOn(api, 'candidates').mockImplementation(async ({ departmentId, enable }) => {
    expect({ departmentId, enable }).toEqual({ departmentId: undefined, enable: true })
    return {
      data: {
        pageData: [local, other],
        total: 2,
      },
    }
  })

  const show = ref(false)
  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp({
    render: () => h(MemberPicker, {
      'show': show.value,
      'onUpdate:show': value => show.value = value,
      'title': '设置负责人',
      'single': true,
      'departmentId': 7,
      'save': async () => {},
    }),
  })
  setupStore(app)
  app.mount(root)

  const scopeSwitch = () => document.querySelector<HTMLElement>('[role="switch"]')
  try {
    show.value = true
    await vi.waitFor(() => expect(document.body.textContent).toContain(local.username))
    expect(scopeSwitch()?.getAttribute('aria-checked')).toBe('true')
    expect(document.body.textContent).not.toContain(other.username)

    scopeSwitch()?.click()
    await vi.waitFor(() => expect(document.body.textContent).toContain(other.username))
    expect(scopeSwitch()?.getAttribute('aria-checked')).toBe('false')

    show.value = false
    await nextTick()
    show.value = true
    await vi.waitFor(() => {
      expect(document.body.textContent).toContain(local.username)
      expect(document.body.textContent).not.toContain(other.username)
      expect(scopeSwitch()?.getAttribute('aria-checked')).toBe('true')
    })
  }
  finally {
    app.unmount()
    root.remove()
    members.mockRestore()
    candidates.mockRestore()
  }
})
