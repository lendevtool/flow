// @vitest-environment jsdom

import type { MailTemplate } from '@/views/pms/mail/api'
import { expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import api from '@/views/pms/mail/api'
import TestMailDrawer from '@/views/pms/mail/TestMailDrawer.vue'

vi.mock('@/views/pms/mail/api', () => ({
  default: {
    send: vi.fn(),
  },
}))

const template: MailTemplate = {
  id: 7,
  code: 'TASK_NOTICE',
  name: '待办提醒',
  accountId: 4,
  bodyFormat: 'MARKDOWN',
  revision: 1,
  enable: true,
  sendMode: 'INDIVIDUAL',
  subjectTemplate: '{{taskName}}',
  bodyTemplate: '{{recipient.name}}，请处理 {{taskName}}。联系邮箱 {{recipient.email}}。',
}

function mount(row: MailTemplate) {
  const container = document.createElement('div')
  document.body.append(container)
  const app = createApp(() => h(TestMailDrawer, { template: row }))
  app.mount(container)
  return () => {
    app.unmount()
    container.remove()
  }
}

async function fill(label: string, value: string) {
  const field = document.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)
  expect(field).not.toBeNull()
  field!.value = value
  field!.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}

function action(label: string) {
  const button = Array.from(document.querySelectorAll('button')).find(button => button.textContent?.trim() === label)
  expect(button).toBeDefined()
  return button!
}

it('requires template variables and keeps the same test request when checking an uncertain result', async () => {
  const send = vi.mocked(api.send).mockReset()
  send.mockRejectedValueOnce(new Error('连接超时'))
    .mockResolvedValueOnce({
      code: 0,
      message: 'OK',
      data: { logId: 12, status: 'PROCESSING' },
    })
    .mockResolvedValueOnce({
      code: 0,
      message: 'OK',
      data: { logId: 12, status: 'ACCEPTED' },
    })
  const unmount = mount(template)
  try {
    await nextTick()
    await fill('测试收件邮箱', 'tester@example.com')
    expect(action('发送测试邮件').disabled).toBe(true)
    await fill('收件人姓名', '测试同事')
    await fill('taskName', '测试待办')
    const sendButton = action('发送测试邮件')
    sendButton.click()
    sendButton.click()

    await vi.waitFor(() => expect(document.body.textContent).toContain('连接超时'))
    expect(send).toHaveBeenCalledTimes(1)
    const first = send.mock.calls[0][0]
    expect(first).toEqual({
      templateCode: 'TASK_NOTICE',
      recipients: [
        { address: 'tester@example.com', name: '测试同事' },
      ],
      variables: { taskName: '测试待办' },
      idempotencyKey: expect.stringMatching(/^test-mail:/),
    })

    action('使用原请求重试').click()
    await vi.waitFor(() => expect(document.body.textContent).toContain('提交中'))
    expect(send.mock.calls[1][0]).toEqual(first)
    action('查询提交结果').click()
    await vi.waitFor(() => expect(document.body.textContent).toContain('已受理'))
    expect(send.mock.calls[2][0]).toEqual(first)
    expect(document.body.textContent).toContain('12')
  }
  finally {
    unmount()
  }
})

it('can test a template with no variables using just an email address', async () => {
  const send = vi.mocked(api.send).mockReset()
  send.mockResolvedValueOnce({
    code: 0,
    message: 'OK',
    data: { logId: 13, status: 'FAILED' },
  })
  const unmount = mount({
    ...template,
    subjectTemplate: '通知',
    bodyTemplate: '测试正文',
    sendMode: 'TOGETHER',
  })
  try {
    await nextTick()
    await fill('测试收件邮箱', 'tester@example.com')
    expect(document.querySelector('input[aria-label="收件人姓名"]')).toBeNull()
    action('发送测试邮件').click()
    await vi.waitFor(() => expect(document.body.textContent).toContain('失败'))
    expect(send.mock.calls[0][0].variables).toEqual({})
    expect(send.mock.calls[0][0].recipients).toEqual([
      { address: 'tester@example.com' },
    ])
  }
  finally {
    unmount()
  }
})
