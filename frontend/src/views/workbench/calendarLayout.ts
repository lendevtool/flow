import type { Period } from './model'
import { calendarPlacement } from './model'

type CalendarDates = Parameters<typeof calendarPlacement>[0]

export function layoutCalendarWeek<T extends CalendarDates>(days: string[], todos: T[], period: Period) {
  const items: { todo: T, column: number, span: number, dueOnly: boolean, lane: number }[] = []
  const hidden = Array.from<number>({ length: days.length }).fill(0)
  const lanes: boolean[][] = []
  for (const todo of todos) {
    const entry = calendarPlacement(todo, days)
    if (!entry)
      continue
    const { column, span } = entry
    let lane = lanes.findIndex(used => !used.slice(column, column + span).some(Boolean))
    if (lane < 0 && (period === 'week' || lanes.length < 3)) {
      lane = lanes.length
      lanes.push(Array.from<boolean>({ length: days.length }).fill(false))
    }
    if (lane < 0) {
      for (let i = column; i < column + span; i++)
        hidden[i]++
      continue
    }
    lanes[lane].fill(true, column, column + span)
    items.push({ todo, ...entry, lane })
  }
  const overflow = hidden.flatMap((count, column) => count
    ? [
        { date: days[column], count, column },
      ]
    : [])
  return { days, items, overflow }
}
