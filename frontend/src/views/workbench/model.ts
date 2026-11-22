import type { Period } from '@/utils/businessDate'
import { datePreset, monday, shiftDays } from '@/utils/businessDate'

export type { Period, Preset } from '@/utils/businessDate'
export { datePreset, formatBusinessToday, shiftDays, shiftPeriod } from '@/utils/businessDate'

export type DimensionKind = 'public' | 'personal'
export type DimensionFilter = Partial<Record<`${DimensionKind}${number}`, string[]>>

export function calendarDays(date: string, period: Period): string[] {
  const [from, to] = period === 'week'
    ? [date, date]
    : datePreset('month', date)
  const start = monday(from!)
  const end = shiftDays(monday(to!), 6)
  const days: string[] = []
  for (let day = start; day <= end; day = shiftDays(day, 1))
    days.push(day)
  return days
}

interface Dates {
  startDate?: string | null
  endDate?: string | null
  dueDate?: string | null
}

export function calendarPlacement(todo: Dates, days: string[]): { column: number, span: number, dueOnly: boolean } | null {
  const hasPlan = Boolean(todo.startDate && todo.endDate)
  const start = hasPlan ? todo.startDate : todo.dueDate
  const end = hasPlan ? todo.endDate : todo.dueDate
  if (!start || !end)
    return null
  const column = days.findIndex(day => day >= start)
  const last = days.findLastIndex(day => day <= end)
  return column < 0 || last < column
    ? null
    : { column, span: last - column + 1, dueOnly: !hasPlan }
}

export function dimensionQuery(input: Record<string, unknown>): DimensionFilter {
  const result: DimensionFilter = {}
  for (const kind of ['public', 'personal'] as const) {
    for (let slot = 1; slot <= 5; slot++) {
      const key = `${kind}${slot}` as const
      const value = input[key]
      const values = (Array.isArray(value)
        ? value
        : [value]).filter(
        (item): item is string => typeof item === 'string' && Boolean(item),
      )
      if (values.length) {
        result[key] = [
          ...new Set(values),
        ]
      }
    }
  }
  return result
}

export function newWorkbenchKey(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0F) | 0x40
  bytes[8] = (bytes[8] & 0x3F) | 0x80
  const hex = [...bytes].map(byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function workbenchError(error: unknown, fallback: string): string {
  if (error && typeof error === 'object' && 'message' in error && typeof error.message === 'string')
    return error.message
  return fallback
}

export const originLabels = {
  MANUAL: '手动',
  BUSINESS: '业务接口',
  SERIES: '周期',
  FLOW: '流程节点',
} as const
export const priorityLabels = { LOW: '低', NORMAL: '普通', HIGH: '高' } as const
export const statusLabels = {
  OPEN: '待处理',
  IN_PROGRESS: '处理中',
  DONE: '已完成',
  CANCELLED: '已取消',
} as const
export const scopeLabels = {
  mine: '我的待办',
  created: '我发起的',
  done: '已完成',
  all: '全部',
} as const
