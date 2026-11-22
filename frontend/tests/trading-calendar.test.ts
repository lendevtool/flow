import type { CalendarDay } from '../src/views/pms/trading-calendar/api'
import { describe, expect, it } from 'vitest'
import { buildYear, daysByDate, parseTradingOffset, previewFingerprint } from '../src/views/pms/trading-calendar/model'

describe('annual trading calendar', () => {
  it('keeps leap day and aligns months with Monday as the first column', () => {
    const months = buildYear(2024)
    expect(months).toHaveLength(12)
    expect(months[1].days.slice(0, 5)).toEqual([
      null,
      null,
      null,
      '2024-02-01',
      '2024-02-02',
    ])
    expect(months[1].days.at(-1)).toBe('2024-02-29')
    expect(months[11].days.at(-1)).toBe('2024-12-31')
  })

  it('retains two market statuses on the same date without inferring data for a third market', () => {
    const closed: CalendarDay = {
      marketCode: 'CN',
      calendarDate: '2026-10-01',
      calendarDate8: '20261001',
      isTradingDay: false,
      holidayName: '国庆节',
      source: 'MANUAL',
      remark: null,
    }
    const open = {
      ...closed,
      marketCode: 'US',
      isTradingDay: true,
      holidayName: null,
    }
    const day = daysByDate([closed, open]).get('2026-10-01')
    expect(day?.get('CN')).toBe(closed)
    expect(day?.get('US')).toBe(open)
    expect(day?.get('HK')).toBeUndefined()
  })

  it('accepts signed offsets and zero while rejecting fractions and out-of-range values', () => {
    expect([
      '-2',
      '+3',
      '0',
      '1.5',
      '2e3',
      '',
      '2147483648',
    ].map(parseTradingOffset))
      .toEqual([-2, 3, 0, null, null, null, null])
  })

  it('blocks confirmation without a valid, error-free preview', () => {
    const result = {
      added: 1,
      changed: 0,
      unchanged: 0,
      errors: [],
      fingerprint: 'abc',
    }
    expect(previewFingerprint(result)).toBe('abc')
    expect(previewFingerprint({
      ...result,
      errors: [
        { row: 2, message: 'Bad date' },
      ],
    })).toBeNull()
    expect(previewFingerprint({ ...result, fingerprint: null })).toBeNull()
  })
})
