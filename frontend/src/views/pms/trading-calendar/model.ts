import type { CalendarDay, CalendarMarket, CalendarPreview } from './api'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'

interface CalendarMonth {
  month: number
  days: (string | null)[]
}

export function buildYear(year: number): CalendarMonth[] {
  return Array.from({ length: 12 }, (_, month) => {
    const blanks = (new Date(Date.UTC(year, month, 1)).getUTCDay() + 6) % 7
    const count = new Date(Date.UTC(year, month + 1, 0)).getUTCDate()
    const prefix = `${year}-${String(month + 1).padStart(2, '0')}-`
    return {
      month,
      days: [
        ...Array.from<null>({ length: blanks }).fill(null),
        ...Array.from({ length: count }, (_, day) => prefix + String(day + 1).padStart(2, '0')),
      ],
    }
  })
}

export function daysByDate(days: CalendarDay[]): Map<string, Map<string, CalendarDay>> {
  const dates = new Map<string, Map<string, CalendarDay>>()
  for (const day of days) {
    const markets = dates.get(day.calendarDate) ?? new Map<string, CalendarDay>()
    markets.set(day.marketCode, day)
    dates.set(day.calendarDate, markets)
  }
  return dates
}

export function parseTradingOffset(input: string): number | null {
  const value = input.trim()
  if (!/^[+-]?\d+$/.test(value))
    return null
  const number = Number(value)
  return number >= -2147483648 && number <= 2147483647
    ? number
    : null
}

export function previewFingerprint(result: CalendarPreview | null): string | null {
  return result && !result.errors.length
    ? result.fingerprint
    : null
}

export const minYear = 1000
export const maxYear = 9999
export type ManagementKind = 'initialize' | 'batch' | 'import'

export function marketIcon(market: CalendarMarket): string {
  const key = market.iconKey?.toUpperCase()
  return key && /^[A-Z]{2}$/.test(key)
    ? String.fromCodePoint(...Array.from(key, letter => 0x1F1E6 + letter.charCodeAt(0) - 65))
    : ''
}

export function marketName(market: CalendarMarket): string {
  return displayName(market.name, market.nameEn) || market.code
}

export function dayClass(day?: CalendarDay): 'trading' | 'closed' | 'missing' {
  return day
    ? (day.isTradingDay ? 'trading' : 'closed')
    : 'missing'
}

export function dayStatus(day?: CalendarDay): string {
  return t(day
    ? (day.isTradingDay ? '交易' : '休市')
    : '无日历数据')
}

export function calendarError(error: unknown, fallback: string): string {
  return typeof error === 'object' && error !== null && 'message' in error && typeof error.message === 'string'
    ? error.message
    : t(fallback)
}
