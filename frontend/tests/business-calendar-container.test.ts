// @vitest-environment jsdom

import type { BusinessCalendarResult, BusinessTodo } from '@/views/workbench/business-todo-calendar/api'
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { request } from '@/utils'
import BusinessTodoCalendar from '@/views/workbench/business-todo-calendar/BusinessTodoCalendar.vue'

afterEach(() => vi.restoreAllMocks())

it('keeps current range fallback and restores only authorized labels when a previous grid finishes late', async () => {
  const staleTodo: BusinessTodo = {
    todoId: '9',
    title: '过期待办响应',
    status: 'OPEN',
    overdue: false,
    startDate: '2026-11-10',
    endDate: '2026-11-10',
    dueDate: null,
    assigneeUserId: 1,
    assigneeName: '负责人',
    recordLabel: 'PO-1',
    taskKindCode: 'APPROVE',
  }
  const rangeTodo = { ...staleTodo, todoId: '9007199254740993', title: '当前范围中的待办' }
  const counts = {
    all: 501,
    pending: 501,
    done: 0,
    cancelled: 0,
  }
  let finishOld: ((result: { data: BusinessCalendarResult }) => void) | undefined
  const lookups: string[][] = []
  const listQueries: URLSearchParams[] = []
  vi.spyOn(request, 'get').mockImplementation(async (url, config) => {
    const query = new URLSearchParams(config?.params)
    if (url.endsWith('/meta')) {
      return {
        data: {
          title: '采购任务',
          context: { id: 'PO-1', label: '订单 1' },
          statuses: [
            { value: 'all', label: '全部' },
            { value: 'pending', label: '待办' },
          ],
          defaultView: 'month',
          dimensions: [
            {
              key: 'supplier',
              label: '供应商',
              primary: true,
              searchable: true,
              multiple: true,
              options: [],
            },
          ],
        },
      }
    }
    if (url.endsWith('/options/supplier')) {
      const selected = query.getAll('value')
      lookups.push(selected)
      const items = selected.includes('retired')
        ? [
            { value: 'retired', label: '历史供应商', enabled: false },
          ]
        : []
      return {
        data: { items, total: items.length },
      }
    }
    if (url.endsWith('/calendar')) {
      if (query.get('status') === 'all') {
        return new Promise((resolve) => {
          finishOld = resolve
        })
      }
      return {
        data: {
          items: [],
          counts,
          unscheduledCount: 2,
          overflow: true,
        },
      }
    }
    listQueries.push(query)
    return {
      data: {
        items: [rangeTodo],
        total: 501,
      },
    }
  })

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/calendar',
        component: BusinessTodoCalendar,
        props: { sceneCode: 'fixture', contextId: 'PO-1' },
        meta: { businessCalendarQuery: true },
      },
      {
        path: '/record',
        component: {
          render: () => h('p', '业务详情'),
        },
      },
    ],
  })
  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp({
    render: () => h(RouterView),
  })
  await router.push('/calendar?date=2026-11-09&view=week&filter.supplier=retired&filter.supplier=hidden')
  app.use(router)
  app.mount(root)

  try {
    await vi.waitFor(() => {
      expect(finishOld).toBeDefined()
      expect(root.textContent).toContain('供应商 · 历史供应商')
      expect(root.textContent).toContain('供应商 · 所选值')
    })
    expect(lookups).toContainEqual(['retired', 'hidden'])
    expect(root.textContent).not.toContain('hidden')
    await router.push({
      path: '/calendar',
      query: { ...router.currentRoute.value.query, status: 'pending' },
    })
    await vi.waitFor(() => expect(root.textContent).toContain(rangeTodo.title))
    expect(root.textContent).toContain('当前日期范围的待办超过日历容量')
    expect(Object.fromEntries(listQueries[0])).toEqual({
      'contextId': 'PO-1',
      'mode': 'range',
      'fromDate': '2026-11-09',
      'toDate': '2026-11-15',
      'status': 'pending',
      'page': '1',
      'size': '20',
      'filter.supplier': 'hidden',
    })

    finishOld?.({
      data: {
        items: [staleTodo],
        counts,
        unscheduledCount: 0,
        overflow: false,
      },
    })
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(root.textContent).toContain(rangeTodo.title)
    expect(root.textContent).not.toContain(staleTodo.title)

    await router.push({
      path: '/calendar',
      query: {
        ...router.currentRoute.value.query,
        calendarList: 'day',
        calendarDay: '2026-11-10',
        calendarPage: '2',
      },
    })
    await vi.waitFor(() => expect(listQueries.at(-1)?.get('date')).toBe('2026-11-10'))
    await router.push('/record')
    expect(root.textContent).toContain('业务详情')
    const requestsBeforeReturn = listQueries.length
    router.back()
    await vi.waitFor(() => {
      expect(listQueries.length).toBeGreaterThan(requestsBeforeReturn)
      expect(listQueries.at(-1)?.get('mode')).toBe('day')
      expect(listQueries.at(-1)?.get('date')).toBe('2026-11-10')
      expect(listQueries.at(-1)?.get('page')).toBe('2')
      expect(root.textContent).toContain(rangeTodo.title)
    })
  }
  finally {
    app.unmount()
    root.remove()
  }
})
