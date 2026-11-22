<template>
  <n-menu
    ref="menu"
    class="side-menu"
    accordion
    :indent="18"
    :collapsed-icon-size="22"
    :collapsed-width="64"
    :collapsed="appStore.collapsed"
    :options="permissionStore.menus"
    :value="activeKey"
    @update:value="handleMenuSelect"
  />
</template>

<script setup>
import { t } from '@/i18n'
import { useAppStore, usePermissionStore } from '@/store'
import { externalNavigationTarget, internalNavigationPath } from '@/utils/navigationTarget'

const router = useRouter()
const route = useRoute()
const appStore = useAppStore()
const permissionStore = usePermissionStore()

const activeKey = computed(() => route.meta?.parentKey || route.name)

const menu = ref(null)
watch(route, async () => {
  await nextTick()
  menu.value?.showOption()
})

function handleMenuSelect(key, item) {
  const target = externalNavigationTarget(item.originPath)
  const path = internalNavigationPath(item.path)
  if (target) {
    const openExternally = () => {
      window.open(target.url, '_blank', 'noopener,noreferrer')
    }
    if (target.kind === 'contact' || !path) {
      openExternally()
      return
    }
    $dialog.confirm({
      type: 'info',
      title: t('请选择打开方式'),
      positiveText: t('外链打开'),
      negativeText: t('在本站内嵌打开'),
      confirm: openExternally,
      cancel: () => router.push(path),
    })
  }
  else if (path) {
    router.push(path)
  }
}
</script>

<style>
.side-menu:not(.n-menu--collapsed) {
  .n-menu-item-content {
    &::before {
      left: 8px;
      right: 8px;
    }
    &.n-menu-item-content--selected::before {
      border-left: 4px solid rgb(var(--primary-color));
    }
  }
}
</style>
