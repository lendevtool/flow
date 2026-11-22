import { hyphenate } from '@vueuse/core'
import { defineStore } from 'pinia'
import { navigationLabel, resourceLabel } from '@/i18n/navigation'
import { externalNavigationTarget, internalNavigationPath } from '@/utils/navigationTarget'

export const usePermissionStore = defineStore('permission', {
  state: () => ({
    accessRoutes: [],
    permissions: [],
    menus: [],
  }),
  getters: {
    resourcesByCode(state) {
      const entries = new Map()
      const pending = [...state.permissions]
      while (pending.length) {
        const item = pending.pop()
        entries.set(item.code, item)
        if (item.children?.length)
          pending.push(...item.children)
      }
      return entries
    },
  },
  actions: {
    /** @param {string} path */
    hasRoute(path) {
      return this.accessRoutes.some(route => route.path === path)
    },
    routeTitle(code, fallback) {
      const item = this.resourcesByCode.get(code)
      return item
        ? resourceLabel(item)
        : navigationLabel(code, fallback)
    },
    updateResourceNames({ id, name, nameEn }) {
      const item = [
        ...this.resourcesByCode.values(),
      ].find(entry => entry.id === id)
      if (item) {
        item.name = name
        item.nameEn = nameEn
      }
    },
    updateResourceOrder(ids) {
      const resources = new Map([
        ...this.resourcesByCode.values(),
      ].map(item => [item.id, item]))
      ids.forEach((id, index) => {
        const item = resources.get(id)
        if (item)
          item.order = index + 1
      })
      this.setPermissions(this.permissions)
    },
    setPermissions(permissions) {
      this.accessRoutes = []
      this.permissions = permissions
      this.menus = this.permissions
        .filter(item => item.type === 'MENU')
        .map(item => this.getMenuItem(item))
        .filter(item => !!item)
        .sort((a, b) => a.order - b.order)
    },
    getMenuItem(item, parent) {
      const route = this.generateRoute(item, item.show ? null : parent?.key)
      if (item.enable && route.path) {
        this.accessRoutes.push(route)
        // 新旧分配页面沿用同一权限资源，存量菜单配置无需迁移。
        if (item.code === 'RoleUser') {
          this.accessRoutes.push({
            name: 'RoleUserTransfer',
            path: '/pms/role/user-transfer/:roleId',
            component: '/src/views/pms/role/RoleUserTransfer.vue',
            meta: { ...route.meta, title: '穿梭分配用户', keepAlive: false },
          })
        }
      }
      const menuItem = {
        label: () => resourceLabel(item),
        key: route.name,
        path: route.path,
        originPath: route.meta.originPath,
        icon: () => h('i', { class: `${route.meta.icon} text-16` }),
        order: item.order ?? 0,
      }
      const children = item.children?.filter(item => item.type === 'MENU') || []
      if (children.length) {
        menuItem.children = children
          .map(child => this.getMenuItem(child, menuItem))
          .filter(item => !!item)
          .sort((a, b) => a.order - b.order)
        if (!menuItem.children.length)
          delete menuItem.children
      }
      if (!item.show)
        return null
      return menuItem
    },
    generateRoute(item, parentKey) {
      const external = externalNavigationTarget(item.path)
      const web = external?.kind === 'web'
      const path = web
        ? `/iframe/${encodeURIComponent(hyphenate(item.code))}`
        : internalNavigationPath(item.path)
      return {
        name: item.code,
        path,
        redirect: external
          ? undefined
          : internalNavigationPath(item.redirect),
        component: web ? '/src/views/iframe/index.vue' : item.component,
        meta: {
          originPath: external?.url,
          icon: `${item.icon}?mask`,
          title: item.name,
          layout: item.layout,
          keepAlive: !!item.keepAlive,
          parentKey,
          btns: item.children
            ?.filter(item => item.type === 'BUTTON')
            .map(item => ({ code: item.code, name: item.name })),
        },
      }
    },
    resetPermission() {
      this.$reset()
    },
  },
})
