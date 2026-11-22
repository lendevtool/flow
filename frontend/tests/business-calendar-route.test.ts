// @vitest-environment jsdom

import { createPinia, setActivePinia } from 'pinia'
import { expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createTabGuard } from '../src/router/guards/tab-guard'
import { routeTabKey } from '../src/router/routeTabKey'
import { useTabStore } from '../src/store'

it('keeps filter changes in one opted-in tab while preserving the host query in its identity', async () => {
  setActivePinia(createPinia())
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/calendar',
        component: {},
        meta: { businessCalendarQuery: true },
      },
      { path: '/workbench', component: {} },
    ],
  })
  createTabGuard(router)

  await router.push('/calendar?contextId=1')
  const key = routeTabKey(router, router.currentRoute.value)
  await router.push('/calendar?contextId=1&date=2026-11-22&view=week&status=done&filter.supplier=4&calendarList=day&calendarDay=2026-11-22&calendarPage=2')
  expect(routeTabKey(router, router.currentRoute.value)).toBe(key)
  expect(useTabStore().tabs.map(tab => tab.path)).toEqual([router.currentRoute.value.fullPath])

  await router.push('/calendar?contextId=2')
  expect(routeTabKey(router, router.currentRoute.value)).not.toBe(key)
  expect(useTabStore().tabs).toHaveLength(2)

  await router.push('/workbench?page=1')
  await router.push('/workbench?page=2')
  expect(useTabStore().tabs).toHaveLength(4)
})
