import type { MailAccount, MailLogDetails, MailTemplate } from '../../src/views/pms/mail/api'
import { mockTime } from '../fixtures.ts'
import { inRange, MockError, object } from '../protocol.ts'
import { at, bool, collection, enabled, matchesAny, number, page, route, text, unique } from './common.ts'

function html(value: string) {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll('\'', '&#39;')
}

export function createMail() {
  const accounts = collection<MailAccount>([
    {
      id: 1,
      code: 'DEMO_MAIL',
      name: '演示邮箱',
      enable: true,
      provider: 'IMAP_SMTP',
      mailboxAddress: 'flow@example.invalid',
      credentialConfigured: true,
      config: {
        smtp: {
          host: 'smtp.example.invalid',
          port: 587,
          username: 'flow@example.invalid',
          tls: 'STARTTLS',
        },
      },
    },
  ])
  const templates = collection<MailTemplate>([
    {
      id: 1,
      code: 'DEMO_NOTICE',
      name: '演示通知模板',
      enable: true,
      accountId: 1,
      subjectTemplate: '流程通知 · {{title}}',
      bodyTemplate: '你好，{{recipient.name}}。\n\n流程已更新：{{title}}。',
      sendMode: 'INDIVIDUAL',
      bodyFormat: 'MARKDOWN',
      revision: 1,
    },
  ])
  const logs = collection<MailLogDetails>([])
  const requests = new Map<string, number>()
  function list<T extends { code: string, name: string, enable: boolean }>(rows: T[], query: URLSearchParams) {
    return page(rows.filter(row => matchesAny(query.get('keyword'), row.code, row.name) && enabled(row.enable, query.get('enable'))), query)
  }
  function accountInput(body: Record<string, unknown>, current?: MailAccount) {
    const code = text(body.code, current?.code)
    unique(accounts.rows().filter(row => row !== current), code, row => row.code)
    const config = body.config === undefined
      ? current?.config ?? {}
      : object(body.config)
    return {
      code,
      name: text(body.name, current?.name),
      enable: bool(body.enable, current?.enable ?? true),
      provider: body.provider === 'GRAPH'
        ? 'GRAPH' as const
        : 'IMAP_SMTP' as const,
      mailboxAddress: text(body.mailboxAddress, current?.mailboxAddress),
      config,
      credentialConfigured: !!body.credentials || (current?.credentialConfigured ?? false),
    }
  }
  function templateInput(body: Record<string, unknown>, current?: MailTemplate): Omit<MailTemplate, 'id'> {
    const code = text(body.code, current?.code)
    unique(templates.rows().filter(row => row !== current), code, row => row.code)
    const accountId = number(body.accountId, current?.accountId ?? 0)
    accounts.get(accountId)
    return {
      code,
      name: text(body.name, current?.name),
      enable: bool(body.enable, current?.enable ?? true),
      accountId,
      subjectTemplate: text(body.subjectTemplate, current?.subjectTemplate),
      bodyTemplate: text(body.bodyTemplate, current?.bodyTemplate),
      sendMode: body.sendMode === 'TOGETHER'
        ? 'TOGETHER'
        : 'INDIVIDUAL',
      bodyFormat: 'MARKDOWN',
      revision: (current?.revision ?? 0) + 1,
    }
  }
  function sent(template: MailTemplate, recipients: { address: string, name: string | null }[], requestId: string, time = mockTime()) {
    const account = accounts.get(template.accountId)
    const batches = template.sendMode === 'TOGETHER'
      ? [recipients]
      : recipients.map(recipient => [recipient])
    return logs.create({
      accountId: account.id,
      accountCode: account.code,
      templateId: template.id,
      templateCode: template.code,
      templateRevision: template.revision,
      senderAddress: account.mailboxAddress,
      provider: account.provider,
      sendMode: template.sendMode,
      callerKey: 'MOCK',
      requestId,
      recipientCount: recipients.length,
      status: 'ACCEPTED',
      createdAt: time,
      finishedAt: time,
      details: batches.map((batch, index) => ({
        id: index + 1,
        sequenceNo: index + 1,
        recipients: batch,
        recipientResults: batch.map(({ address }) => ({ address, status: 'ACCEPTED' })),
        status: 'ACCEPTED',
        providerRequestId: 'mock-delivery',
        errorCode: null,
        errorMessage: null,
        startedAt: time,
        finishedAt: time,
      })),
    })
  }
  sent(templates.get(1), [
    { address: 'recipient@example.invalid', name: '演示收件人' },
  ], 'mock-mail-example', mockTime(-1))
  const routes = [
    route('GET', '/mail/accounts', ({ query }) => list(accounts.rows(), query)),
    route('GET', '/mail/accounts/options', () => accounts.rows().map(({ id, code, name, enable }) => ({ id, code, name, enable }))),
    route('POST', '/mail/accounts', ({ body }) => accounts.create(accountInput(body))),
    route('PUT', at('/mail/accounts/:id'), ({ params, body }) => {
      const row = accounts.get(params[1])
      return Object.assign(row, accountInput(body, row))
    }),
    route('GET', '/mail/templates', ({ query }) => list(templates.rows(), query)),
    route('POST', '/mail/templates', ({ body }) => templates.create(templateInput(body))),
    route('PUT', at('/mail/templates/:id'), ({ params, body }) => {
      const row = templates.get(params[1])
      return Object.assign(row, templateInput(body, row))
    }),
    route('POST', '/mail/templates/preview', ({ body }) => {
      const values = object(body.variables ?? {})
      const recipient = object(body.recipient ?? {})
      values['recipient.email'] = recipient.address
      values['recipient.name'] = recipient.name
      const render = (template: unknown) =>
        text(template).replace(/\{\{\s*([\w.]+)\s*\}\}/g, (_, variable) => text(values[variable]))
      return {
        subject: render(body.subjectTemplate),
        html: `<pre>${html(render(body.bodyTemplate))}</pre>`,
      }
    }),
    route('POST', '/mail/send', ({ body }) => {
      const template = templates.rows().find(row => row.enable && row.code === body.templateCode)
      if (!template || !accounts.get(template.accountId).enable || !Array.isArray(body.recipients) || !body.recipients.length)
        throw new MockError(400, '请选择有效的邮件模板与收件人')
      const recipients = body.recipients.map((recipient) => {
        const input = object(recipient)
        const address = text(input.address).trim()
        if (!/^[^\s@]+@[^\s@]+$/.test(address))
          throw new MockError(400, '邮箱地址格式有误')
        return { address, name: text(input.name) || null }
      })
      const key = text(body.idempotencyKey)
      const previous = requests.get(key)
      const log = previous
        ? logs.get(previous)
        : sent(template, recipients, key || crypto.randomUUID())
      if (key)
        requests.set(key, log.id)
      return { logId: log.id, status: log.status }
    }),
    route('GET', '/mail/logs', ({ query }) => page(logs.rows().reverse().filter(row =>
      (!query.has('accountId') || row.accountId === Number(query.get('accountId')))
      && (!query.has('templateId') || row.templateId === Number(query.get('templateId')))
      && (!query.has('status') || row.status === query.get('status'))
      && inRange(row.createdAt.slice(0, 10), query.get('from')?.slice(0, 10) ?? null, query.get('to')?.slice(0, 10) ?? null),
    ), query)),
    route('GET', '/mail/logs/options', () => ({
      accounts: accounts.rows().map(({ id, code, name }) => ({ id, code, name })),
      templates: templates.rows().map(({ id, code, name }) => ({ id, code, name })),
    })),
    route('GET', at('/mail/logs/:id'), ({ params }) => logs.get(params[1])),
  ]
  return { templates, routes }
}

export type MockMail = ReturnType<typeof createMail>
