<template>
  <ProConfigProvider
    class="wh-full"
    :locale="proLocale"
    :date-locale="naiveDateLocale"
    :theme="appStore.isDark ? darkTheme : undefined"
    :theme-overrides="themeOverrides"
  >
    <router-view v-if="Layout" v-slot="{ Component, route: curRoute }">
      <component :is="Layout">
        <transition name="fade-slide" mode="out-in" appear>
          <KeepAlive :include="keepAliveNames">
            <component :is="Component" v-if="!tabStore.reloading" :key="routeTabKey(router, curRoute)" />
          </KeepAlive>
        </transition>
      </component>

      <LayoutSetting v-if="appStore.layoutSettingVisible" class="fixed right-12 top-1/2 z-999" />
    </router-view>
    <PersonDetailsDrawer />
  </ProConfigProvider>
</template>

<script setup>
import { darkTheme } from 'naive-ui'
import { enUS, ProConfigProvider, zhCN } from 'pro-naive-ui'
import { LayoutSetting } from '@/components'
import PersonDetailsDrawer from '@/components/common/PersonDetailsDrawer.vue'
import { language, naiveDateLocale } from '@/i18n'
import { routeTabKey } from '@/router/routeTabKey'
import { useAppStore, useTabStore } from '@/store'

const proLocale = computed(() => language.value === 'en-US'
  ? enUS
  : zhCN)
watchEffect(() => {
  document.documentElement.lang = language.value
})

const layouts = new Map()
function getLayout(name) {
  // 利用map将加载过的layout缓存起来，防止重新加载layout导致页面闪烁
  if (layouts.get(name))
    return layouts.get(name)
  const layout = markRaw(defineAsyncComponent(() => import(`@/layouts/${name}/index.vue`)))
  layouts.set(name, layout)
  return layout
}

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const themeOverrides = computed(() => ({
  ...appStore.naiveThemeOverrides,
  DataTable: {
    fontSizeSmall: '12px',
    thPaddingSmall: '6px',
    tdPaddingSmall: '2px 6px 2px 6px',
    thPaddingLarge: '16px 12px',
    tdPaddingLarge: '16px 12px',
  },
}))
const Layout = computed(() => {
  if (!route.matched?.length)
    return null
  return getLayout(route.meta?.layout || appStore.layout)
})

const tabStore = useTabStore()
const keepAliveNames = computed(() => {
  return tabStore.tabs.filter(item => item.keepAlive).map(item => item.name)
})

watchEffect(() => {
  appStore.setThemeColor(appStore.primaryColor, appStore.isDark)
})
</script>
