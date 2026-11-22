<template>
  <main class="h-full flex-col flex-1 overflow-hidden bg-#f5f6fb dark:bg-#121212">
    <AppCard
      v-if="showHeader"
      class="sticky top-0 z-1 min-h-60 flex items-center justify-between px-24"
      border-b="1px solid light_border dark:dark_border"
    >
      <slot v-if="$slots.header" name="header" />
      <template v-else>
        <div class="flex items-center">
          <slot name="title-prefix">
            <template v-if="back">
              <div
                class="mr-16 flex cursor-pointer items-center text-16 opacity-60 transition-all-300 hover:opacity-40"
                @click="router.back()"
              >
                <i class="i-material-symbols:arrow-left-alt" />
                <span class="ml-4">{{ t('返回') }}</span>
              </div>
            </template>
          </slot>

          <n-tooltip v-if="tabStore.activeTab === route.fullPath">
            <template #trigger>
              <n-button
                quaternary
                circle
                size="small"
                type="primary"
                class="mr-8"
                :aria-label="t('重新加载')"
                @click="tabStore.reloadTab(route.fullPath, route.meta?.keepAlive)"
              >
                <template #icon>
                  <i class="i-mdi:refresh text-18" aria-hidden="true" />
                </template>
              </n-button>
            </template>
            {{ t('重新加载') }}
          </n-tooltip>
          <div v-else class="mr-12 h-16 w-4 rounded-l-2 bg-primary" />
          <h2 class="font-normal">
            {{ title ?? permissions.routeTitle(route.name, route.meta?.title) }}
          </h2>
          <slot name="title-suffix" />
        </div>
        <slot name="action" />
      </template>
    </AppCard>
    <AppCard class="cus-scroll m-12 h-0 flex-1 rounded-8 p-24" bordered>
      <slot />
    </AppCard>

    <slot name="footer">
      <AppCard v-if="showFooter" class="flex-shrink-0 py-12">
        <TheFooter />
      </AppCard>
    </slot>
  </main>
</template>

<script setup>
import { t } from '@/i18n'
import { usePermissionStore, useTabStore } from '@/store'

defineProps({
  back: {
    type: Boolean,
    default: false,
  },
  showFooter: {
    type: Boolean,
    default: false,
  },
  showHeader: {
    type: Boolean,
    default: true,
  },
  title: {
    type: String,
    default: undefined,
  },
})
const permissions = usePermissionStore()
const tabStore = useTabStore()
const route = useRoute()
const router = useRouter()
</script>
