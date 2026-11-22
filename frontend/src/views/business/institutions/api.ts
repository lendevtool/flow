import { request } from '@/utils/http'

interface Response<T> {
  data: T
}

interface Page<T> {
  pageData: T[]
  total: number
}

export interface Paging {
  pageNo: number
  pageSize: number
}

export interface InstitutionQuery extends Paging {
  keyword?: string
  typeItemId?: number
  countryRegionItemId?: number
  enabled?: boolean
}

export interface InstitutionOption {
  id: number
  code: string
  name: string
  nameEn: string | null
  enabled: boolean
}

export interface InstitutionOptions {
  types: InstitutionOption[]
  countries: InstitutionOption[]
  approvalOptions?: { types: string[], countries: string[], fields: string[] } | null
  fieldDefinitions?: Record<string, { name: string, nameEn: string | null, required: boolean, maxLength: number | null }>
}

export interface InstitutionFields {
  code: string
  nameZh: string
  nameEn: string | null
  typeItemId: number | null
  countryRegionItemId: number | null
  registrationId: string | null
  shortNameZh: string | null
  shortNameEn: string | null
  officeAddress: string | null
  website: string | null
  remark: string | null
}

export interface InstitutionWrite extends InstitutionFields {
  version?: number
}

export interface Institution extends InstitutionFields {
  id: number
  typeItemId: number
  countryRegionItemId: number
  enabled: boolean
  version: number
  createdBy: number
  updatedBy: number
  createdAt: string
  updatedAt: string
}

export type ChangeStatus = 'PENDING' | 'APPLIED' | 'CONFLICT' | 'CANCELLED'
export interface InstitutionChange {
  id: number
  institutionId: number
  flowInstanceId: number
  status: ChangeStatus
  baseValues: Record<string, string | null>
  resolvedAt: string | null
}

export interface ChangeWrite {
  version: number
  reason: string
  fields: Record<string, string | null>
}

const prefix = '/institutions'
const inlineErrors = { needTip: false, headers: {} }

export default {
  options: (): Promise<Response<InstitutionOptions>> =>
    request.get(`${prefix}/options`, inlineErrors),
  page: (params: InstitutionQuery): Promise<Response<Page<Institution>>> =>
    request.get(prefix, { ...inlineErrors, params }),
  detail: (id: number, signal: AbortSignal): Promise<Response<Institution>> =>
    request.get(`${prefix}/${id}`, { ...inlineErrors, signal }),
  create: (data: InstitutionWrite): Promise<Response<Institution>> =>
    request.post(prefix, data),
  update: (id: number, data: InstitutionWrite): Promise<Response<Institution>> =>
    request.put(`${prefix}/${id}`, data),
  status: (id: number, enabled: boolean, version: number): Promise<Response<Institution>> =>
    request.put(`${prefix}/${id}/status`, { enabled, version }),
  changes: (id: number, params: Paging, signal: AbortSignal): Promise<Response<Page<InstitutionChange>>> =>
    request.get(`${prefix}/${id}/changes`, { ...inlineErrors, params, signal }),
  changeDetail: (id: number, changeId: number, signal: AbortSignal): Promise<Response<InstitutionChange>> =>
    request.get(`${prefix}/${id}/changes/${changeId}`, { ...inlineErrors, signal }),
  submit: (id: number, data: ChangeWrite): Promise<Response<InstitutionChange>> =>
    request.post(`${prefix}/${id}/changes`, data),
}
