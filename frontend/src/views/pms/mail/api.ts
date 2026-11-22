import { request } from '@/utils'

export type Provider = 'IMAP_SMTP' | 'GRAPH'
export type Tls = 'SSL' | 'STARTTLS'
export type SendMode = 'INDIVIDUAL' | 'TOGETHER'
export type MailStatus = 'PENDING' | 'PROCESSING' | 'ACCEPTED' | 'PARTIAL' | 'FAILED' | 'UNKNOWN'

export interface MailOption {
  id: number
  code: string
  name: string
  enable: boolean
}

export interface MailEndpoint {
  host: string
  port: number
  username: string
  tls: Tls
}

export interface GraphSettings {
  tenantId: string
  clientId: string
  mailboxId?: string
}

interface AccountInput {
  code: string
  name: string
  enable: boolean
  provider: Provider
  mailboxAddress: string
  config: { imap?: MailEndpoint, smtp?: MailEndpoint, graph?: GraphSettings }
}

export interface MailAccount extends AccountInput {
  id: number
  credentialConfigured: boolean
}

export interface AccountWrite extends AccountInput {
  credentials?: { imapPassword?: string, smtpPassword?: string, clientSecret?: string }
}

export interface TemplateWrite {
  code: string
  name: string
  enable: boolean
  accountId: number
  subjectTemplate: string
  bodyTemplate: string
  sendMode: SendMode
}

export interface MailTemplate extends TemplateWrite {
  id: number
  bodyFormat: 'MARKDOWN'
  revision: number
}

interface Recipient {
  address: string
  name: string | null
}

export interface MailLog {
  id: number
  accountId: number
  templateId: number
  accountCode: string
  templateCode: string
  templateRevision: number
  senderAddress: string
  provider: Provider
  sendMode: SendMode
  callerKey: string
  requestId: string | null
  recipientCount: number
  status: MailStatus
  createdAt: string
  finishedAt: string | null
}

export interface Submission {
  id: number
  sequenceNo: number
  recipients: Recipient[]
  recipientResults: { address: string, status: MailStatus }[]
  status: MailStatus
  providerRequestId: string | null
  errorCode: string | null
  errorMessage: string | null
  startedAt: string | null
  finishedAt: string | null
}

export interface MailLogDetails extends MailLog {
  details: Submission[]
}

type LogOption = Pick<MailOption, 'id' | 'code' | 'name'>
export interface LogOptions {
  accounts: LogOption[]
  templates: LogOption[]
}

export interface PreviewQuery {
  subjectTemplate: string
  bodyTemplate: string
  sendMode: SendMode
  variables: Record<string, string>
  recipient?: { address?: string, name?: string }
}

export interface MailPreview {
  subject: string
  html: string
}

export interface MailSendRequest {
  templateCode: string
  recipients: { address: string, name?: string }[]
  variables: Record<string, string>
  idempotencyKey: string
}

export interface MailSendResult {
  logId: number
  status: MailStatus
}

interface Response<T> {
  code: number
  message: string
  data: T
}

interface Pagination {
  pageNo: number
  pageSize: number
}

export interface ListFilters {
  keyword: string | null
  enable: 1 | 0 | null
}

interface LogQuery extends Pagination {
  accountId: number | null
  templateId: number | null
  status: MailStatus | null
  from?: string
  to?: string
}

const accounts = '/mail/accounts'
const templates = '/mail/templates'
const logs = '/mail/logs'

async function page<T>(path: string, params: object) {
  const response = await request.get<unknown, Response<{ records: T[], total: number }>>(path, { params })
  const { records, total } = response.data
  return {
    ...response,
    data: { pageData: records, total },
  }
}

function listParams(params: ListFilters & Pagination) {
  return {
    ...params,
    keyword: params.keyword?.trim() || undefined,
    enable: params.enable === null
      ? undefined
      : params.enable === 1,
  }
}

export default {
  accounts: (params: ListFilters & Pagination) => page<MailAccount>(accounts, listParams(params)),
  accountOptions: (): Promise<Response<MailOption[]>> => request.get(`${accounts}/options`),
  createAccount: (data: AccountWrite) => request.post(accounts, data),
  updateAccount: (id: number, data: AccountWrite) => request.put(`${accounts}/${id}`, data),
  templates: (params: ListFilters & Pagination) => page<MailTemplate>(templates, listParams(params)),
  createTemplate: (data: TemplateWrite) => request.post(templates, data),
  updateTemplate: (id: number, data: TemplateWrite) => request.put(`${templates}/${id}`, data),
  preview: (data: PreviewQuery, signal: AbortSignal): Promise<Response<MailPreview>> => {
    const options = { needTip: false, signal }
    return request.post(`${templates}/preview`, data, options)
  },
  send: (data: MailSendRequest): Promise<Response<MailSendResult>> => {
    const options = { needTip: false, timeout: 90_000 }
    return request.post('/mail/send', data, options)
  },
  logs: (params: LogQuery) => page<MailLog>(logs, params),
  logOptions: (): Promise<Response<LogOptions>> => request.get(`${logs}/options`),
  log: (id: number, signal: AbortSignal): Promise<Response<MailLogDetails>> => request.get(`${logs}/${id}`, { signal }),
}
