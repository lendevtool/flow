import type { AxiosRequestConfig } from 'axios'
import type { FieldDefinition, Option } from '@/views/pms/field/api'
import type { Instance } from '@/views/workbench/workflow/api'
import { request } from '@/utils'

export type WorkflowStatus = 'DRAFT' | 'PUBLISHED' | 'RETIRED'
export type HandlingMode = 'PRIMARY' | 'BROADCAST' | 'ALL' | 'MULTI_ALL'

export interface WorkflowTemplateFilters {
  keyword: string | null
  status: WorkflowStatus | null
}

export interface WorkflowRole {
  code: string
  name: string
  nameEn?: string
  hasEnabledMembers: boolean
}

export interface WorkflowNode {
  key: string
  parentKey: string | null
  order: number
  title: string
  roleCode: string | null
  roleCodes?: string[] | null
  mode: HandlingMode | null
  editableFields: string[] | null
  requiredFields: string[]
  dueOffsetDays: number | null
  onApprovedSceneCode?: string | null
  onRejectedSceneCode?: string | null
}

export interface WorkflowDraft {
  code: string
  name: string
  version?: number
  sourceDefinitionId?: number
  fields: string[]
  nodes: WorkflowNode[]
}

export interface WorkflowNotificationScene {
  sceneCode: string
  nameCn: string
  nameEn: string | null
}

export interface WorkflowNotificationWarning {
  nodeKey: string
  triggerType: 'APPROVED' | 'REJECTED'
  sceneCode: string
  reason: 'NO_GROUPS'
}

export interface WorkflowDefinition extends Omit<WorkflowDraft, 'fields' | 'version' | 'sourceDefinitionId'> {
  id: number
  revision: number
  version: number
  status: WorkflowStatus
  publishedAt: string | null
  fields: { code: string, config?: FieldDefinition['config'], options?: Option[] }[]
  notificationWarnings?: WorkflowNotificationWarning[]
}

interface Page<T> {
  pageData: T[]
  total: number
}

interface Response<T> { data: T }

const base = '/workflow/definitions'

export const workflowDefinitions = {
  list: (query: WorkflowTemplateFilters & { pageNo: number, pageSize: number }): Promise<Response<Page<WorkflowDefinition>>> => request.get(base, {
    params: {
      ...query,
      keyword: query.keyword?.trim(),
      latestOnly: true,
    },
  }),
  detail: (id: number): Promise<Response<WorkflowDefinition>> => request.get(`${base}/${id}`),
  history: (id: number, pageNo: number): Promise<Response<Page<WorkflowDefinition>>> =>
    request.get(`${base}/${id}/history`, {
      params: { pageNo },
    }),
  instances: (id: number, pageNo: number): Promise<Response<Page<Instance>>> =>
    request.get(`${base}/${id}/instances`, {
      params: { pageNo },
    }),
  roles: (): Promise<Response<WorkflowRole[]>> => request.get(`${base}/roles`),
  notificationScenes(): Promise<Response<WorkflowNotificationScene[]>> {
    const config: AxiosRequestConfig & { needTip: boolean } = { needTip: false }
    return request.get(`${base}/notification-scenes`, config)
  },
  fields: (keyword: string, pageNo: number): Promise<Response<Page<FieldDefinition>>> =>
    request.get(`${base}/fields`, {
      params: { keyword, pageNo },
    }),
  fieldsByCode: (codes: string[]): Promise<Response<FieldDefinition[]>> => request.get(`${base}/fields/by-code`, {
    params: {
      codes: codes.join(','),
    },
  }),
  create: (body: WorkflowDraft): Promise<Response<WorkflowDefinition>> => request.post(base, body),
  edit: (id: number, body: WorkflowDraft): Promise<Response<WorkflowDefinition>> => request.patch(`${base}/${id}`, body),
  transition: (id: number, action: 'publish' | 'retire', version: number): Promise<Response<WorkflowDefinition>> =>
    request.post(`${base}/${id}/${action}`, { version }),
}
