export type Preset = 'today' | 'week' | 'month' | 'past' | 'future' | 'any'
export type Period = 'week' | 'month'
export type DateRange = [string | null, string | null] | null

export function formatBusinessToday(now = new Date()): string {
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now)
}

function utcDate(date: string) {
  return new Date(`${date}T00:00:00Z`)
}

function iso(date: Date) {
  return date.toISOString().slice(0, 10)
}

export function shiftDays(date: string, days: number): string {
  const value = utcDate(date)
  value.setUTCDate(value.getUTCDate() + days)
  return iso(value)
}

export function monday(date: string) {
  return shiftDays(date, -((utcDate(date).getUTCDay() + 6) % 7))
}

export function shiftPeriod(date: string, period: Period, amount: number): string {
  if (period === 'week')
    return shiftDays(date, 7 * amount)
  const value = utcDate(date)
  value.setUTCDate(1)
  value.setUTCMonth(value.getUTCMonth() + amount)
  return iso(value)
}

export function datePreset(preset: Preset, today: string): [string | null, string | null] {
  switch (preset) {
    case 'today': return [today, today]
    case 'week': return [
      monday(today),
      shiftDays(monday(today), 6),
    ]
    case 'month': return [
      shiftPeriod(today, 'month', 0),
      shiftDays(shiftPeriod(today, 'month', 1), -1),
    ]
    case 'past': return [
      shiftDays(today, -6),
      today,
    ]
    case 'future': return [
      today,
      shiftDays(today, 6),
    ]
    case 'any': return [null, null]
  }
}

/** 查询允许单侧日期，起止日期同时存在时校验先后顺序。 */
export function isDateRangeReversed(range: DateRange): boolean {
  const [from, to] = range ?? []
  return !!from && !!to && from > to
}
