import type { AppPatch, AppWrite, EndpointImport, EndpointStatus, GrantWrite, RevokeMode, SecretStatus } from '@/api/open-api/api'
import type { DateRange } from '@/utils/businessDate'
import { t } from '@/i18n'
import { shiftDays } from '@/utils/businessDate'

export function openApiPermissions(role: string | undefined, buttons: unknown) {
  const can = (code: string) => role === 'SUPER_ADMIN' || (Array.isArray(buttons) && buttons.some(button => button?.code === code))
  return {
    apps: can('OpenApiAppManage'),
    endpoints: can('OpenApiEndpointPublish'),
    grants: can('OpenApiGrantManage'),
    secrets: can('OpenApiSecretManage'),
    calls: can('OpenApiCallLogView'),
  }
}

export const endpointStatuses: readonly EndpointStatus[] = ['REGISTERED', 'PUBLISHED', 'OFFLINE']
export const endpointStatusType = {
  REGISTERED: 'default',
  PUBLISHED: 'success',
  OFFLINE: 'warning',
} as const

export function endpointStatusLabel(status: EndpointStatus) {
  return t({ REGISTERED: '已登记', PUBLISHED: '已发布', OFFLINE: '已下线' }[status])
}

export const endpointModuleNames: Readonly<Partial<Record<string, string>>> = {
  'trading-calendar': '交易日历',
  'workbench': '工作台',
  'institution': '机构管理',
  'field-definition': '字段管理',
  'param': '参数管理',
  'department': '组织架构',
  'user': '用户管理',
  'role': '角色管理',
  'permission': '资源管理',
  'document': '文档中心',
}

export function endpointModuleLabel(moduleCode: string) {
  return t(endpointModuleNames[moduleCode] ?? moduleCode)
}

export const managedEndpointPrefix = '/open/v1/managed/'
export const managedEndpointSlugMax = 256 - managedEndpointPrefix.length
export const endpointCodePattern = /^[a-z0-9][a-z0-9._-]{0,95}$/
export const managedEndpointSlugPattern = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

export interface EndpointImportDraft {
  displayName: string
  endpointCode: string
  slug: string
  responseFields: string[]
}

export function endpointImportPayload(
  source: { sourcePath: string, fields: readonly { name: string }[], wholeResponse?: boolean },
  draft: EndpointImportDraft,
): EndpointImport | null {
  const displayName = draft.displayName.trim()
  const endpointCode = draft.endpointCode.trim()
  const slug = draft.slug.trim()
  if (!displayName || displayName.length > 128 || !endpointCodePattern.test(endpointCode)
    || slug.length > managedEndpointSlugMax || !managedEndpointSlugPattern.test(slug)) {
    return null
  }
  const payload: EndpointImport = {
    sourcePath: source.sourcePath,
    displayName,
    endpointCode,
    pathTemplate: `${managedEndpointPrefix}${slug}`,
  }
  if (source.wholeResponse)
    return payload

  const selectedFields = new Set(draft.responseFields)
  const responseFields = source.fields.map(field => field.name).filter(name => selectedFields.has(name))
  if (!responseFields.length || selectedFields.size !== responseFields.length)
    return null
  return { ...payload, responseFields }
}

export function appPatch(initial: AppWrite & { version: number }, current: AppWrite): AppPatch {
  const patch: AppPatch = { version: initial.version }
  if (current.name !== initial.name)
    patch.name = current.name
  if (current.ownerUserId !== initial.ownerUserId)
    patch.ownerUserId = current.ownerUserId
  if (current.description !== initial.description)
    patch.description = current.description
  if (current.rateLimitCount !== initial.rateLimitCount)
    patch.rateLimitCount = current.rateLimitCount
  if (current.rateLimitWindowSeconds !== initial.rateLimitWindowSeconds)
    patch.rateLimitWindowSeconds = current.rateLimitWindowSeconds
  return patch
}

export interface GrantDraft {
  limitCount: number | null
  limitWindow: number | null
}

export const maxQuota = 2_147_483_647

export function grantPayload(draft: GrantDraft, version: number | null): GrantWrite {
  return {
    status: 'GRANTED',
    rateLimitCount: draft.limitCount,
    rateLimitWindowSeconds: draft.limitWindow,
    version,
  }
}

export type SecretAction = 'activate' | RevokeMode

export function secretActions(status: SecretStatus, expiresAt: string, tokensRevokedAt: string | null, now: number): SecretAction[] {
  if (status === 'CANDIDATE') {
    return Date.parse(expiresAt) > now
      ? ['activate', 'ABANDON', 'COMPROMISED']
      : ['ABANDON', 'COMPROMISED']
  }
  if (status === 'RETIRING')
    return ['RETIRE', 'COMPROMISED']
  return tokensRevokedAt
    ? []
    : ['COMPROMISED']
}

export function callTimeRange(range: DateRange): { from?: string, to?: string } | null {
  const [from, to] = range ?? []
  if (from && to && from > to)
    return null
  // 接口的结束时间为开区间，传次日的北京时间零点。
  const start = from
    ? new Date(`${from}T00:00:00+08:00`)
    : undefined
  const end = to
    ? new Date(`${shiftDays(to, 1)}T00:00:00+08:00`)
    : undefined
  if (start && end && end.getTime() - start.getTime() > 31 * 86_400_000)
    return null
  const result: { from?: string, to?: string } = {}
  if (start)
    result.from = start.toISOString()
  if (end)
    result.to = end.toISOString()
  return result
}

const timeFormatter = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai',
  dateStyle: 'short',
  timeStyle: 'medium',
})

export function businessTime(value: string | null) {
  return value
    ? timeFormatter.format(new Date(value))
    : '—'
}

export function isConflict(error: unknown) {
  return !!error && typeof error === 'object' && 'code' in error && error.code === 409
}
