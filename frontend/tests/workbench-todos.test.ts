import { describe, expect, it } from 'vitest'
import { calendarDays, calendarPlacement, datePreset, dimensionQuery, formatBusinessToday } from '../src/views/workbench/model'

describe('workbench dates', () => {
  it('uses Shanghai today and Monday as the beginning of the week', () => {
    expect(formatBusinessToday(new Date('2026-11-09T17:00:00Z'))).toBe('2026-11-10')
    expect(datePreset('week', '2026-11-01')).toEqual(['2026-10-26', '2026-11-01'])
  })

  it('counts today in both seven-day presets and uses the last day of a leap month', () => {
    expect(datePreset('past', '2024-02-26')).toEqual(['2024-02-20', '2024-02-26'])
    expect(datePreset('future', '2024-02-26')).toEqual(['2024-02-26', '2024-03-03'])
    expect(datePreset('month', '2024-02-26')).toEqual(['2024-02-01', '2024-02-29'])
  })

  it('uses the visible Monday to Sunday grid, capped at 42 days', () => {
    const month = calendarDays('2026-11-10', 'month')
    expect(month).toHaveLength(42)
    expect(month.at(0)).toBe('2026-10-26')
    expect(month.at(-1)).toBe('2026-12-06')
    expect(calendarDays('2026-11-10', 'week').at(0)).toBe('2026-11-09')
  })

  it('clips a plan to each visible week and uses due dates for due-only entries', () => {
    const week = calendarDays('2026-11-10', 'week')
    expect(calendarPlacement({ startDate: '2026-11-06', endDate: '2026-11-10', dueDate: '2026-11-13' }, week))
      .toEqual({ column: 0, span: 2, dueOnly: false })
    expect(calendarPlacement({ dueDate: '2026-11-15' }, week)).toEqual({ column: 6, span: 1, dueOnly: true })
    expect(calendarPlacement({ dueDate: '2026-11-16' }, week)).toBeNull()
    expect(calendarPlacement({}, week)).toBeNull()
  })
})

describe('workbench dimensions', () => {
  it('round-trips repeated keys while removing duplicates and unsupported slots', () => {
    expect(dimensionQuery({
      public1: ['10', '20', '10'],
      personal3: 'a',
      public6: 'b',
      personal2: [null, ''],
    }))
      .toEqual({
        public1: ['10', '20'],
        personal3: ['a'],
      })
  })
})
