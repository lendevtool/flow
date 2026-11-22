import { request } from '@/utils'

interface Response<T> {
  data: T
}

export interface CalendarMarket {
  code: string
  name: string
  nameEn: string | null
  timeZone: string
  iconKey: string | null
}

export interface CalendarDay {
  marketCode: string
  calendarDate: string
  calendarDate8: string
  isTradingDay: boolean
  holidayName: string | null
  source: string
  remark: string | null
}

export interface CalendarPreview {
  added: number
  changed: number
  unchanged: number
  errors: { row: number | null, message: string }[]
  fingerprint: string | null
}

export interface CalendarInitialization {
  year: number
  marketCodes: string[]
  fingerprint?: string
}

export type HolidayMode = 'KEEP' | 'SET' | 'CLEAR'
export type CalendarBatch = {
  marketCodes: string[]
  isTradingDay: boolean
  holidayMode: HolidayMode
  holidayName?: string
  fingerprint?: string
} & ({ dates: string[] } | { startDate: string, endDate: string })

export type CalendarImportMode = 'SINGLE' | 'MULTI'
export type CalendarImport = { file: File, fingerprint?: string } & (
  { mode: 'SINGLE', marketCode: string } | { mode: 'MULTI' }
)

const prefix = '/trading-calendar'
const inlineErrors = { needTip: false, headers: {} }
type Phase = 'preview' | 'apply'

export default {
  markets: (): Promise<Response<CalendarMarket[]>> => request.get(`${prefix}/markets`, inlineErrors),
  year: (year: number, marketCodes: string[]): Promise<Response<CalendarDay[]>> =>
    request.get(`${prefix}/year`, {
      ...inlineErrors,
      params: {
        year,
        marketCodes: marketCodes.join(','),
      },
    }),
  status: (marketCode: string, date: string): Promise<Response<CalendarDay>> =>
    request.get(`${prefix}/status`, {
      ...inlineErrors,
      params: { marketCode, date },
    }),
  offset: (marketCode: string, date: string, n: number): Promise<Response<{ date: string }>> =>
    request.get(`${prefix}/offset`, {
      ...inlineErrors,
      params: { marketCode, date, n },
    }),
  initialize: (phase: Phase, payload: CalendarInitialization): Promise<Response<CalendarPreview>> =>
    request.post(`${prefix}/initialize/${phase}`, payload, inlineErrors),
  batch: (phase: Phase, payload: CalendarBatch): Promise<Response<CalendarPreview>> =>
    request.post(`${prefix}/batch/${phase}`, payload, inlineErrors),
  template: (mode: CalendarImportMode): Promise<Blob> =>
    request.get(`${prefix}/import/template`, {
      ...inlineErrors,
      params: { mode },
      responseType: 'blob',
    }),
  import: (phase: Phase, payload: CalendarImport): Promise<Response<CalendarPreview>> => {
    const form = new FormData()
    form.append('file', payload.file)
    form.append('mode', payload.mode)
    if (payload.mode === 'SINGLE')
      form.append('marketCode', payload.marketCode)
    if (payload.fingerprint)
      form.append('fingerprint', payload.fingerprint)
    return request.post(`${prefix}/import/${phase}`, form, inlineErrors)
  },
}
