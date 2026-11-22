import type { Priority, ScheduleDates } from './api'
import { request } from '@/utils'

interface Response<T> {
  data: T
}

export interface SeriesWrite {
  title: string
  description?: string | null
  assigneeUserId?: number | null
  priority: Priority
  frequency: 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY' | 'TRADING_DAILY'
  intervalValue: number
  weekdaysMask?: number
  monthDayMode?: 'ANCHOR' | 'MONTH_END'
  anchorDate: string
  untilDate?: string | null
  durationDays?: number | null
  dueOffsetDays?: number | null
  marketCode?: string | null
  startTradeOffset?: number | null
  endTradeOffset?: number | null
  dueTradeOffset?: number | null
  dimensions: Record<number, number>
}

export interface Series extends SeriesWrite {
  id: number
  ownerName: string
  status: 'ACTIVE' | 'STOPPED' | 'FINISHED'
  ruleRevision: number
  nextBaseDate: string | null
  blockedDate: string | null
  blockReason: string | null
}

export interface SeriesPreview {
  occurrences: (ScheduleDates & { seriesBaseDate: string, occurrenceDate: string })[]
  coverageStart?: string
  coverageEnd?: string
  blockedDate?: string
  blockReason?: string
}

export interface SeriesImpact {
  token: string
  cutoffDate: string
  affected: { id: number, title: string, occurrenceDate: string, version: number }[]
  schedule?: SeriesPreview
}

export interface FlowStep {
  stepNo: number
  title: string
  assigneeUserId?: number | null
  dueOffsetDays?: number | null
}

export interface FlowDefinitionWrite {
  code: string
  name: string
  steps: FlowStep[]
}

export interface FlowDefinition extends FlowDefinitionWrite {
  id: number
  revision: number
  version: number
  status: 'DRAFT' | 'PUBLISHED' | 'RETIRED'
}

export interface Flow {
  id: number
  title: string
  status: 'RUNNING' | 'DONE' | 'CANCELLED'
  version: number
  currentStepNo: number
  ownerUserId?: number | null
  allowedActions?: string[]
  steps: (FlowStep & { assigneeName: string, todoId: number | null, status: string })[]
}

const options = { needTip: false, headers: {} }
const series = '/workbench/todo-series'
const definitions = '/workbench/flow-definitions'
const flows = '/workbench/flows'

function seriesOptions(managed: boolean) {
  return {
    ...options,
    params: { managed },
  }
}

export default {
  series: (managed = false): Promise<Response<Series[]>> => request.get(series, seriesOptions(managed)),
  previewSeries: (body: SeriesWrite): Promise<Response<SeriesPreview>> => request.post(`${series}/preview`, body, options),
  createSeries: (body: SeriesWrite): Promise<Response<Series>> => request.post(series, body, options),
  impact: (id: number, body: { action: 'EDIT' | 'STOP', rule?: SeriesWrite }, managed = false): Promise<Response<SeriesImpact>> =>
    request.post(`${series}/${id}/change-preview`, body, seriesOptions(managed)),
  editSeries: (id: number, token: string, rule: SeriesWrite, managed = false): Promise<Response<Series>> =>
    request.patch(`${series}/${id}`, { token, rule }, seriesOptions(managed)),
  stopSeries: (id: number, token: string, managed = false): Promise<Response<Series>> =>
    request.post(`${series}/${id}/stop`, { token }, seriesOptions(managed)),
  definitions: (published: boolean): Promise<Response<FlowDefinition[]>> =>
    request.get(definitions, {
      ...options,
      params: { published },
    }),
  createDefinition: (body: FlowDefinitionWrite): Promise<Response<FlowDefinition>> => request.post(definitions, body, options),
  editDefinition: (id: number, body: Omit<FlowDefinitionWrite, 'code'> & { version: number }): Promise<Response<FlowDefinition>> =>
    request.patch(`${definitions}/${id}`, body, options),
  definitionAction: (id: number, action: 'publish' | 'retire', version: number): Promise<Response<FlowDefinition>> =>
    request.post(`${definitions}/${id}/${action}`, { version }, options),
  startFlow: (body: { requestKey: string, definitionId: number, title: string, assignees: Record<number, number>, dimensions: Record<number, number> }): Promise<Response<Flow>> =>
    request.post(flows, body, options),
  flow: (id: string): Promise<Response<Flow>> => request.get(`${flows}/${encodeURIComponent(id)}`, options),
  completeStep: (id: number, todoId: number, version: number): Promise<Response<Flow>> =>
    request.post(`${flows}/${id}/complete`, { todoId, version }, options),
  cancelFlow: (id: number, version: number, reason: string): Promise<Response<Flow>> =>
    request.post(`${flows}/${id}/cancel`, { version, reason }, options),
  assignStep: (id: number, version: number, stepNo: number, assigneeUserId: number): Promise<Response<Flow>> =>
    request.post(`${flows}/${id}/assign`, { version, stepNo, assigneeUserId }, options),
}
