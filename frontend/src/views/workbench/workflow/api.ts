import type { AxiosResponse } from 'axios'
import type { WorkflowDefinition } from '@/views/pms/workflow/api'
import { request } from '@/utils'

export interface AttachmentRef {
  documentId: string
  versionId: string
}

export interface Attachment extends AttachmentRef {
  name: string
  sizeBytes: number
}

export type WorkflowValue = string | boolean | string[] | AttachmentRef[] | null
export type WorkflowValues = Record<string, WorkflowValue>

export type AttemptStatus = 'ACTIVE' | 'WAITING_ASSIGNMENT' | 'DONE' | 'CANCELLED' | 'WITHDRAWN' | 'RETURNED'

export interface CurrentNode {
  nodeKey: string
  title: string
  status: 'ACTIVE' | 'WAITING_ASSIGNMENT'
  roleName: string | null
  assigneeNames: string[]
}

export interface NodeAttempt {
  id: number
  nodeKey: string
  stageNo: number
  attemptNo: number
  status: AttemptStatus
  roleName: string | null
  processedNames: string[]
  assigneeNames: string[]
}

export interface Instance {
  id: number
  definitionId: number
  title: string
  ownerName: string
  currentStepNo: number
  status: 'RUNNING' | 'DONE' | 'CANCELLED'
  version: number
  startedAt: string
  currentNodes: CurrentNode[]
}

export interface Task {
  id: number
  todoId: number
  nodeKey: string
  identity: 'PRIMARY' | 'DELEGATE' | 'BROADCAST' | 'COUNTERSIGN'
  editableFields: string[]
  requiredFields: string[]
  canSubmit: boolean
  rejectTargets: string[]
}

export interface Detail {
  instance: Instance
  definition: WorkflowDefinition
  values: WorkflowValues
  nodes: NodeAttempt[]
  tasks: Task[]
  canRecall: boolean
  canCancel: boolean
  canRetry: boolean
  attachmentNames: Record<string, string>
  sourceUrl?: string | null
  roleNames: Record<string, string>
  plannedAssignees: Record<string, string[]>
}

export interface History {
  id: number
  type: string
  nodeKey: string | null
  targetNodeKey: string | null
  actorName: string
  identity: Task['identity'] | null
  reason: string | null
  opinion: string | null
  createdAt: string
  attachments: Record<string, AttachmentRef[]>
  attachmentNames: Record<string, string>
}

export interface Member { id: number, name: string, enabled: boolean }
export interface Handlers { members: Member[], primaryUserId: number | null, backupUserIds: number[] }
export interface HandlerSummary extends Omit<Handlers, 'members'> {
  primaryEnabled: boolean
  requiresPrimaryHandler: boolean
}
export interface Delegation {
  id: number
  delegateUserId: number
  delegateName: string
  startsAt: string
  endsAt: string
  status: 'ACTIVE' | 'ENDED' | 'SCHEDULED' | 'CANCELLED'
}
interface DelegatedTodo { todoId: number, flowId: number, title: string }
export interface DelegationDetail { delegation: Delegation, pending: DelegatedTodo[], forwarded: DelegatedTodo[] }

export type Scope = 'MINE' | 'STARTED' | 'APPROVED' | 'DONE' | 'ALL'
export interface InstanceQuery {
  scope: Scope
  pageNo: number
  pageSize: number
  title?: string
  ownerName?: string
  startedFrom?: string
  startedTo?: string
}
export const historyPageSize = 18
export type Action = 'submit' | 'reject' | 'recall' | 'cancel' | 'retry-assignment'
interface Response<T> { data: T }
interface Page<T> { pageData: T[], total: number }
interface InstancePage extends Page<Instance> { scopeCounts: Record<Scope, number> }

const base = '/workflow'
const instances = `${base}/instances`
const downloadOptions = { responseType: 'blob' as const, keepAttachmentResponse: true }
const delegations = `${base}/delegations`

export default {
  catalog: (params: { pageNo: number, pageSize: number }): Promise<Response<Page<WorkflowDefinition>>> =>
    request.get(`${base}/catalog/page`, { params }),
  list: (params: InstanceQuery): Promise<Response<InstancePage>> => request.get(instances, { params }),
  start: (body: { definitionId: number, title: string, requestKey: string }): Promise<Response<Detail>> => request.post(instances, body),
  detail: (id: number): Promise<Response<Detail>> => request.get(`${instances}/${id}`),
  history: (id: number, pageNo: number): Promise<Response<Page<History>>> => request.get(`${instances}/${id}/history`, {
    params: { pageNo, pageSize: historyPageSize },
  }),
  act: (id: number, action: Action, body: {
    requestKey: string
    version: number
    todoId?: number
    targetNodeKey?: string
    reason?: string
    opinion?: string
    fields?: WorkflowValues
  }): Promise<Response<Detail>> => request.post(`${instances}/${id}/actions/${action}`, body),
  upload: (id: number, taskId: number, field: string, file: File): Promise<Response<Attachment>> => {
    const data = new FormData()
    data.append('file', file)
    return request.post(`${instances}/${id}/tasks/${taskId}/attachments/${encodeURIComponent(field)}`, data)
  },
  download: (id: number, ref: AttachmentRef) =>
    request.get<Blob, AxiosResponse<Blob>>(`${instances}/${id}/attachments/${ref.documentId}/${ref.versionId}`, downloadOptions),
  handlers: (role: number): Promise<Response<Handlers>> => request.get(`${base}/roles/${role}/handlers`),
  handlerSummary: (role: number, signal: AbortSignal): Promise<Response<HandlerSummary>> => {
    const options = { signal, needTip: false }
    return request.get(`${base}/roles/${role}/handlers/summary`, options)
  },
  saveHandlers: (role: number, data: Omit<Handlers, 'members'>): Promise<Response<Handlers>> => request.put(`${base}/roles/${role}/handlers`, data),
  candidates: (keyword = ''): Promise<Response<Member[]>> => request.get(`${delegations}/candidates`, {
    params: { keyword },
  }),
  delegations: (): Promise<Response<Delegation[]>> => request.get(delegations),
  addDelegation: (body: { delegateUserId: number, startsAt: string, endsAt: string }): Promise<Response<Delegation>> => request.post(delegations, body),
  delegation: (id: number): Promise<Response<DelegationDetail>> => request.get(`${delegations}/${id}`),
  endDelegation: (id: number): Promise<Response<Delegation>> => request.post(`${delegations}/${id}/cancel`),
  forward: (id: number, todoIds: number[]): Promise<Response<{ todoId: number, status: string }[]>> => request.post(`${delegations}/${id}/forward`, { todoIds }),
}
