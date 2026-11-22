// @vitest-environment jsdom

import { describe, expect, it } from 'vitest'
import { effectScope } from 'vue'
import { businessCalendarQuery, readBusinessCalendarListQuery, readBusinessCalendarQuery } from '../src/views/workbench/business-todo-calendar/query'
import { layoutCalendarWeek } from '../src/views/workbench/calendarLayout'
import { calendarDays } from '../src/views/workbench/model'
import { useWorkbenchRequest } from '../src/views/workbench/useWorkbench'

describe('shared todo calendar', () => {
  it('counts every hidden span on each affected day', () => {
    const days = ['2026-11-09', '2026-11-10']
    const items = Array.from({ length: 4 }, (_, id) => ({ id, startDate: days[0], endDate: days[1] }))
    expect(layoutCalendarWeek(days, items, 'month').overflow).toEqual([
      { date: days[0], count: 1, column: 0 },
      { date: days[1], count: 1, column: 1 },
    ])
    expect(layoutCalendarWeek(days, items, 'week').items).toHaveLength(4)
  })

  it('ignores a response that arrives after the query has changed', async () => {
    const scope = effectScope()
    const request = scope.run(() => useWorkbenchRequest<string | null>(null))!
    let resolveOld!: (response: { data: string }) => void
    const old = request.run(() => new Promise((resolve) => {
      resolveOld = resolve
    }))
    request.reset()
    await request.run(async () => ({ data: 'new' }))
    resolveOld({ data: 'old' })
    await old
    expect(request.data.value).toBe('new')
    scope.stop()
  })
})

describe('business calendar URL', () => {
  it('round-trips declared filters, status, date and view while preserving host parameters', () => {
    const dimensions = [
      { key: 'supplier', multiple: true },
    ]
    const query = {
      'date': '2026-11-11',
      'view': 'week',
      'status': 'done',
      'filter.supplier': ['3', '4', '3'],
      'filter.unknown': '1',
      'tab': 'todos',
    }
    const state = readBusinessCalendarQuery(query, 'month', dimensions)
    expect(state).toEqual({
      date: '2026-11-11',
      view: 'week',
      status: 'done',
      filters: {
        supplier: ['3', '4'],
      },
    })
    expect(businessCalendarQuery(query, state)).toEqual({
      'date': '2026-11-11',
      'view': 'week',
      'status': 'done',
      'filter.supplier': ['3', '4'],
      'tab': 'todos',
    })
  })

  it('rejects invalid dates and strips excess values from single-choice filters', () => {
    const today = '2026-11-22'
    const state = readBusinessCalendarQuery(
      {
        'date': '2026-02-30',
        'view': 'day',
        'status': 'OPEN',
        'filter.supplier': ['3', '4'],
      },
      'month',
      [
        { key: 'supplier', multiple: false },
      ],
      today,
    )
    expect(state).toEqual({
      date: today,
      view: 'month',
      status: 'all',
      filters: {
        supplier: ['3'],
      },
    })
  })
})

it('restores the open day and page from the return URL', () => {
  const dates = calendarDays('2026-11-09', 'week')
  const list = readBusinessCalendarListQuery({
    calendarList: 'day',
    calendarDay: '2026-11-10',
    calendarPage: '2',
  }, dates)
  expect(list).toEqual({ mode: 'day', day: '2026-11-10', page: 2 })
  expect(readBusinessCalendarListQuery({ calendarList: 'day', calendarDay: '2026-11-02', calendarPage: '2' }, dates))
    .toEqual({ mode: 'calendar', day: '', page: 1 })
})
