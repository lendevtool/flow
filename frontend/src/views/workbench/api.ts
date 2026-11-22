import type { CalendarMarket } from '../pms/trading-calendar/api'
import type { DimensionFilter, originLabels, priorityLabels, scopeLabels, statusLabels } from './model'
import { request } from '@/utils'

interface Response<T> {
  data: T
}

export type Scope = keyof typeof scopeLabels
export type Origin = keyof typeof originLabels
export type Priority = keyof typeof priorityLabels
export type Status = keyof typeof statusLabels
export type ScheduleMode = 'NONE' | 'FIXED' | 'TRADING'
export interface Assignee { id: number, name: string }
export type TodoAction = 'start' | 'complete' | 'cancel' | 'assign'
export type TodoQuery = DimensionFilter & {
  scope: Scope
  keyword?: string
  origin?: Origin
  status?: Status | 'OVERDUE' | 'TODAY' | 'UNSCHEDULED'
  dateField?: 'due' | 'plan'
  fromDate?: string
  toDate?: string
  page?: number
  size?: number
  includeCompleted?: boolean
}

export interface ScheduleDates {
  startDate: string | null
  endDate: string | null
  dueDate: string | null
}

export interface SchedulePreview extends ScheduleDates {
  coverageStart?: string | null
  coverageEnd?: string | null
}

export interface ScheduleInput extends Partial<ScheduleDates> {
  scheduleMode: ScheduleMode
  marketCode?: string | null
  tradeBaseDate?: string | null
  startTradeOffset?: number | null
  endTradeOffset?: number | null
  dueTradeOffset?: number | null
}

export interface TodoWrite extends ScheduleInput {
  title: string
  description?: string | null
  assigneeUserId?: number | null
  priority: Priority
  dimensions: Record<number, number>
  preview?: ScheduleDates
}

export interface Todo extends Omit<TodoWrite, keyof ScheduleDates>, ScheduleDates {
  id: number
  origin: Origin
  status: Status
  ownerUserId: number | null
  ownerName: string | null
  assigneeUserId: number
  assigneeName: string
  sourceModule: string | null
  businessType: string | null
  businessId: string | null
  sourceLabel?: string | null
  sourceUrl?: string | null
  personalDimensions: Record<number, string>
  seriesId: number | null
  version: number
  createdAt: string
  closedAt: string | null
  allowedActions: string[]
}

export type CalendarTodo = Pick<Todo, 'id' | 'title' | 'status' | 'startDate' | 'endDate' | 'dueDate'>

export interface DimensionOption {
  name: string
  enabled: boolean
}

export interface PublicDimension {
  slot: number
  name: string
  enabled: boolean
  options: (DimensionOption & { id: number })[]
}

export interface PersonalDimension {
  slot: number
  name: string
  enabled: boolean
  version?: number
  options: (DimensionOption & { key: string })[]
}

export interface Statistics {
  key: string | null
  name: string
  total: number
  open: number
  done: number
  overdue: number
}

const prefix = '/workbench/todos'
const options = { needTip: false, headers: {} }

function queryParams(query: TodoQuery & { groupBy?: string }) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    for (const item of Array.isArray(value)
      ? value
      : [value]) {
      if (item !== undefined && item !== null && item !== '')
        params.append(key, String(item))
    }
  }
  return { ...options, params }
}

export default {
  list: (query: TodoQuery): Promise<Response<{ records: Todo[], total: number, scopeCounts: Record<Scope, number> }>> =>
    request.get(prefix, queryParams(query)),
  detail: (id: number): Promise<Response<Todo>> => request.get(`${prefix}/${id}`, options),
  managedDetail: (id: number): Promise<Response<Todo>> => request.get(`${prefix}/manage/${id}`, options),
  managedAssign: (id: number, version: number, assigneeUserId: number): Promise<Response<Todo>> =>
    request.post(`${prefix}/manage/${id}/assign`, { version, assigneeUserId }, options),
  create: (body: TodoWrite & { requestKey: string, personalDimensions?: Record<number, string | null> }): Promise<Response<Todo>> =>
    request.post(prefix, body, options),
  edit: (id: number, body: TodoWrite & { version: number }): Promise<Response<Todo>> =>
    request.patch(`${prefix}/${id}`, body, options),
  action: (id: number, action: TodoAction, body: { version: number, reason?: string, assigneeUserId?: number }): Promise<Response<Todo>> =>
    request.post(`${prefix}/${id}/actions/${action}`, body, options),
  calendar: (query: TodoQuery): Promise<Response<CalendarTodo[]>> =>
    request.get(`${prefix}/calendar`, queryParams(query)),
  statistics: (query: TodoQuery, groupBy: string): Promise<Response<Statistics[]>> =>
    request.get(`${prefix}/statistics`, queryParams({ ...query, groupBy })),
  publicDimensions: (): Promise<Response<PublicDimension[]>> => request.get(`${prefix}/public-dimensions`, options),
  personalDimensions: (): Promise<Response<PersonalDimension[]>> => request.get(`${prefix}/personal-dimensions`, options),
  savePersonalDimension: ({ slot, ...body }: PersonalDimension): Promise<Response<PersonalDimension>> =>
    request.put(`${prefix}/personal-dimensions/${slot}`, body, options),
  markPersonal: (id: number, body: Record<number, string | null>): Promise<Response<Todo>> =>
    request.patch(`${prefix}/${id}/personal-dimensions`, body, options),
  preview: (body: ScheduleInput): Promise<Response<SchedulePreview>> => request.post(`${prefix}/schedule-preview`, body, options),
  markets: (): Promise<Response<CalendarMarket[]>> => request.get(`${prefix}/markets`, options),
  assignees: (): Promise<Response<Assignee[]>> => request.get(`${prefix}/assignees`, options),
}
