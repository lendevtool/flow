import type { RouteLocationNormalized, Router } from 'vue-router'

/** 业务日历的视图参数可分享，同一业务对象仍使用同一个页面实例和标签页。 */
export function routeTabKey(router: Router, route: RouteLocationNormalized): string {
  if (route.meta.businessCalendarQuery !== true)
    return route.fullPath

  const query = Object.fromEntries(Object.entries(route.query).filter(([key]) =>
    ![
      'date',
      'view',
      'status',
      'calendarList',
      'calendarDay',
      'calendarPage',
    ].includes(key) && !key.startsWith('filter.'),
  ))
  return router.resolve({ path: route.path, query, hash: route.hash }).fullPath
}
