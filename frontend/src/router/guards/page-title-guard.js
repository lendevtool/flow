import { watchEffect } from 'vue'
import { usePermissionStore } from '@/store'

const baseTitle = import.meta.env.VITE_TITLE

export function createPageTitleGuard(router) {
  const permissions = usePermissionStore()
  watchEffect(() => {
    const { name, meta } = router.currentRoute.value
    const pageTitle = permissions.routeTitle(name, meta.title)
    document.title = pageTitle
      ? `${pageTitle} | ${baseTitle}`
      : baseTitle
  })
}
