import type { AxiosRequestConfig } from 'axios'
import { request } from '@/utils'

interface Response<T> {
  code: number
  message: string
  data: T
}

export interface Page<T> {
  pageData: T[]
  total: number
}

export interface Paging {
  pageNo: number
  pageSize: number
}

export interface SearchPage extends Paging {
  keyword?: string
}

interface Group {
  id: number
  name: string
  description: string | null
  enabled: boolean
  version: number
}

export interface GroupRow extends Group {
  userCount: number
  departmentCount: number
  usageCount: number
  updatedAt: string
}

export interface Recipient {
  userId: number
  nameCn: string | null
  nameEn: string | null
  username: string
}

export type SourceStatus = 'ACTIVE' | 'DISABLED' | 'MISSING'

export interface UserSource extends Omit<Recipient, 'username'> {
  username: string | null
  status: SourceStatus
}

export interface DepartmentSelection {
  departmentId: number
  includeDescendants: boolean
}

export interface DepartmentSource extends DepartmentSelection {
  path: string
  status: SourceStatus
}

export interface Sources {
  users: UserSource[]
  departments: DepartmentSource[]
}

export type GroupDetail = Group & Sources

export interface DepartmentOption {
  departmentId: number
  parentId: number | null
  name: string
  sortOrder: number
}

export interface GroupOption {
  id: number
  name: string
}

export interface SceneUsage {
  sceneCode: string
  nameCn: string
  nameEn: string | null
}

export type SceneType = 'BUSINESS' | 'WORKFLOW_SYSTEM' | 'WORKFLOW_EXTRA'

interface SceneSettings extends SceneUsage {
  sceneType: SceneType
  enabled: boolean
  titleTemplate: string | null
  summaryTemplate: string | null
  mailTemplateId: number | null
  version: number
}

export interface SceneRow extends SceneSettings {
  groupCount: number
}

export interface SceneDetail extends SceneSettings {
  groups: GroupOption[]
}

export type WorkflowSceneWrite = Omit<SceneSettings, 'sceneType' | 'version'>

export interface WorkflowSceneUsages {
  publishedDefinitionCount: number
  runningInstanceCount: number
}

interface WorkflowMailTemplate {
  id: number
  code: string
  name: string
  enable: boolean
}

export type DeliveryStatus = 'PENDING' | 'PROCESSING' | 'RETRY' | 'DONE' | 'PARTIAL' | 'SKIPPED' | 'FAILED'
export type DeliveryKind = 'NODE_APPROVED' | 'NODE_REJECTED' | 'TASK_ASSIGNED_MAIL' | 'TASK_RETURNED_MAIL'

export interface DeliveryRow {
  id: number
  flowInstanceId: number
  nodeInstanceId: number
  eventId: number | null
  taskId: number | null
  sceneCode: string
  kind: DeliveryKind
  status: DeliveryStatus
  attemptCount: number
  sceneVersion: number | null
  createdAt: string
  preparedAt: string | null
  finishedAt: string | null
  lastErrorCode: string | null
}

export interface DeliveryRecipient {
  recipientUserId: number
  inAppStatus: 'PENDING' | 'WRITTEN' | 'SKIPPED' | 'FAILED'
  messageId: number | null
  mailStatus: 'OFF' | 'PENDING' | 'ACCEPTED' | 'SKIPPED' | 'FAILED' | 'UNKNOWN'
  mailLogId: number | null
  inAppErrorCode: string | null
  mailErrorCode: string | null
}

interface DeliveryDetail {
  delivery: DeliveryRow
  recipients: DeliveryRecipient[]
}

interface DeliveryQuery extends Paging {
  flowInstanceId?: number
  sceneCode?: string
  status?: DeliveryStatus
  from?: string
  to?: string
}

interface SourceWrite {
  userIds: number[]
  departments: DepartmentSelection[]
}

export interface GroupWrite extends SourceWrite {
  name: string
  description: string | null
}

interface DraftPreview extends SourceWrite, Paging {
  groupId?: number
}

const base = '/notification-groups'

function get<T>(path: string, params?: object, signal?: AbortSignal): Promise<Response<T>> {
  const config = { params, signal, needTip: false }
  return request.get(`${base}${path}`, config)
}

const writeConfig: AxiosRequestConfig & { needTip: boolean } = { needTip: false }

export default {
  groups: (params: SearchPage & { enabled?: boolean }) => get<Page<GroupRow>>('', params),
  group: (id: number, signal: AbortSignal) => get<GroupDetail>(`/${id}`, undefined, signal),
  create: (data: GroupWrite): Promise<Response<GroupDetail>> => request.post(base, data, writeConfig),
  update: (id: number, data: GroupWrite & { expectedVersion: number }): Promise<Response<GroupDetail>> =>
    request.put(`${base}/${id}`, data, writeConfig),
  setEnabled: (id: number, data: { enabled: boolean, expectedVersion: number }): Promise<Response<GroupDetail>> =>
    request.put(`${base}/${id}/status`, data, writeConfig),
  recipients: (id: number, params: Paging, signal: AbortSignal) => get<Page<Recipient>>(`/${id}/recipients`, params, signal),
  preview: (data: DraftPreview, signal: AbortSignal): Promise<Response<Page<Recipient>>> => {
    const config = { ...writeConfig, signal }
    return request.post(`${base}/preview`, data, config)
  },
  usages: (id: number, params: Paging, signal: AbortSignal) => get<Page<SceneUsage>>(`/${id}/usages`, params, signal),
  userOptions: (params: SearchPage, signal: AbortSignal) => get<Page<Recipient>>('/candidates/users', params, signal),
  departmentOptions: (signal: AbortSignal) => get<DepartmentOption[]>('/candidates/departments', undefined, signal),
  groupOptions: (params: SearchPage, signal: AbortSignal) => get<Page<GroupOption>>('/options', params, signal),
  scenes: (params: Paging) => get<Page<SceneRow>>('/scenes', params),
  scene: (sceneCode: string, signal: AbortSignal) => get<SceneDetail>(`/scenes/${sceneCode}`, undefined, signal),
  replaceSceneGroups: (sceneCode: string, data: { expectedVersion: number, groupIds: number[] }): Promise<Response<SceneDetail>> =>
    request.put(`${base}/scenes/${sceneCode}/groups`, data, writeConfig),
  createWorkflowScene: (data: WorkflowSceneWrite): Promise<Response<SceneDetail>> =>
    request.post(`${base}/scenes/workflow`, data, writeConfig),
  updateWorkflowScene: (sceneCode: string, data: WorkflowSceneWrite & { expectedVersion: number }): Promise<Response<SceneDetail>> =>
    request.put(`${base}/scenes/workflow/${sceneCode}`, data, writeConfig),
  workflowSceneUsages: (sceneCode: string, signal: AbortSignal) =>
    get<WorkflowSceneUsages>(`/scenes/workflow/${sceneCode}/usages`, undefined, signal),
  workflowMailTemplates: (signal: AbortSignal) => get<WorkflowMailTemplate[]>('/scenes/workflow/mail-templates', undefined, signal),
  workflowDeliveries: (params: DeliveryQuery) => get<Page<DeliveryRow>>('/workflow-deliveries', params),
  workflowDelivery: (id: number, signal: AbortSignal) => get<DeliveryDetail>(`/workflow-deliveries/${id}`, undefined, signal),
}
