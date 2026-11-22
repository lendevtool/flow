import type { ScheduleDates, Status } from '../api'
import type { Period } from '../model'
import type { BusinessCalendarState, BusinessCalendarStatus } from './query'
import { request } from '@/utils'
import { filterQuery } from './query'

interface Response<T> { data: T }
export interface Page<T> { items: T[], total: number }
export interface BusinessOption { value: string, label: string, enabled: boolean }
export interface BusinessDimension {
  key: string
  label: string
  primary: boolean
  multiple: boolean
  searchable: boolean
  options: BusinessOption[]
}

export interface BusinessCalendarMeta {
  title: string
  context: { id: string, label: string } | null
  defaultView: Period
  statuses: { value: BusinessCalendarStatus, label: string }[]
  dimensions: BusinessDimension[]
}

export interface BusinessTodo extends ScheduleDates {
  todoId: string
  title: string
  status: Status
  overdue: boolean
  assigneeUserId: number
  assigneeName: string
  taskKindCode: string
  recordLabel: string | null
}

export interface BusinessTodoDetail extends BusinessTodo {
  closedAt: string | null
  businessUrl: string | null
  workUrl: string | null
}

export interface BusinessCalendarResult {
  items: BusinessTodo[]
  counts: Record<BusinessCalendarStatus, number>
  unscheduledCount: number
  overflow: boolean
}

interface Range { fromDate: string, toDate: string }
export type BusinessListMode = ({ mode: 'range' } & Range) | { mode: 'day', date: string } | { mode: 'unscheduled' }
type Filters = Pick<BusinessCalendarState, 'filters' | 'status'>
type Param = string | number | string[] | undefined

export function businessTodoCalendarApi(sceneCode: string, contextId?: string) {
  const prefix = `/workbench/business-todo-calendars/${encodeURIComponent(sceneCode)}`

  function get<T>(path: string, query: Record<string, Param> = {}): Promise<Response<T>> {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries({ contextId, ...query })) {
      for (const item of Array.isArray(value)
        ? value
        : [value]) {
        if (item !== undefined && item !== '')
          params.append(key, String(item))
      }
    }
    const config = { needTip: false, params, headers: {} }
    return request.get(`${prefix}/${path}`, config)
  }

  return {
    meta: () => get<BusinessCalendarMeta>('meta'),
    options: (key: string, keyword: string, page: number, values?: string[]) =>
      get<Page<BusinessOption>>(`options/${encodeURIComponent(key)}`, {
        keyword,
        page,
        size: values ? 50 : 20,
        value: values,
      }),
    calendar: ({ filters, status }: Filters, range: Range) =>
      get<BusinessCalendarResult>('calendar', {
        status,
        ...range,
        ...filterQuery(filters),
      }),
    items: ({ filters, status }: Filters, mode: BusinessListMode, page: number) =>
      get<Page<BusinessTodo>>('items', {
        status,
        ...mode,
        page,
        size: 20,
        ...filterQuery(filters),
      }),
    detail: (todoId: string) => get<BusinessTodoDetail>(`items/${encodeURIComponent(todoId)}`),
  }
}
