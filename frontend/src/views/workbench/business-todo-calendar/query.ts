import type { LocationQuery } from 'vue-router'
import type { Period } from '../model'
import { formatBusinessToday } from '../model'

export type BusinessCalendarStatus = 'all' | 'pending' | 'done' | 'cancelled'

export interface BusinessCalendarState {
  date: string
  view: Period
  status: BusinessCalendarStatus
  filters: Record<string, string[]>
}

export function readBusinessCalendarQuery(
  query: LocationQuery,
  defaultView: Period,
  dimensions: { key: string, multiple: boolean }[],
  today = formatBusinessToday(),
): BusinessCalendarState {
  const { date, view, status } = query
  const parsed = typeof date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(date)
    ? new Date(`${date}T00:00:00Z`)
    : null
  const validDate = parsed && Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === date
  const filters = Object.fromEntries(dimensions.flatMap(({ key, multiple }) => {
    const raw = query[`filter.${key}`]
    const values = [
      ...new Set((Array.isArray(raw)
        ? raw
        : [raw])
        .filter((value): value is string => typeof value === 'string' && value.length > 0)),
    ]
      .slice(0, multiple ? 50 : 1)
    return values.length
      ? [
          [key, values],
        ]
      : []
  }))

  return {
    date: validDate && typeof date === 'string'
      ? date
      : today,
    view: view === 'week' || view === 'month'
      ? view
      : defaultView,
    status: status === 'pending' || status === 'done' || status === 'cancelled'
      ? status
      : 'all',
    filters,
  }
}

export function filterQuery(filters: BusinessCalendarState['filters']): Record<string, string[]> {
  return Object.fromEntries(Object.entries(filters).map(([key, values]) => [`filter.${key}`, values]))
}

export function businessCalendarQuery(query: LocationQuery, state: BusinessCalendarState): LocationQuery {
  const hostQuery = Object.fromEntries(Object.entries(query).filter(([key]) => !key.startsWith('filter.')))
  return {
    ...hostQuery,
    date: state.date,
    view: state.view,
    status: state.status,
    ...filterQuery(state.filters),
  }
}

export type BusinessCalendarList = 'calendar' | 'day' | 'unscheduled'

export function readBusinessCalendarListQuery(query: LocationQuery, days: string[]): {
  mode: BusinessCalendarList
  day: string
  page: number
} {
  const { calendarList, calendarDay, calendarPage } = query
  const day = calendarList === 'day' && typeof calendarDay === 'string' && days.includes(calendarDay)
    ? calendarDay
    : ''
  const current = typeof calendarPage === 'string' && /^[1-9]\d*$/.test(calendarPage)
    ? Number(calendarPage)
    : 1
  const page = current <= 2_147_483_647
    ? current
    : 1
  if (day)
    return { mode: 'day', day, page }
  if (calendarList === 'unscheduled')
    return { mode: 'unscheduled', day: '', page }
  return {
    mode: 'calendar',
    day: '',
    page: calendarList === 'day'
      ? 1
      : page,
  }
}
