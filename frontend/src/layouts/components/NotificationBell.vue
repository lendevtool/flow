<template>
  <n-popover :show="show" trigger="click" placement="bottom-end" @update:show="show = $event">
    <template #trigger>
      <n-button
        text
        class="notification-trigger"
        :aria-label="count ? t('站内消息，{0} 条未读', count) : t('站内消息')"
        :aria-expanded="show"
      >
        <n-badge :value="count" :max="99" :show-zero="false">
          <i class="i-fe:bell block text-20" aria-hidden="true" />
        </n-badge>
      </n-button>
    </template>

    <section :aria-label="t('站内消息')" class="notification-popover">
      <div class="mb-12 flex flex-wrap items-center justify-between gap-8">
        <h2 class="m-0 text-16 font-600">
          {{ t('站内消息') }}
        </h2>
        <n-checkbox v-model:checked="unreadOnly">
          {{ t('仅未读') }}
        </n-checkbox>
      </div>
      <n-alert v-if="failed" type="error" class="mb-12">
        {{ t('消息加载失败') }}
        <n-button text @click="loadPage(nextBeforeId ?? undefined)">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-alert v-if="markFailed" type="error" class="mb-12">
        {{ t('标记已读失败，请重试') }}
      </n-alert>
      <n-spin :show="loading">
        <n-empty v-if="!items.length && nextBeforeId === null && !loading && !failed" :description="t(unreadOnly ? '没有未读消息' : '暂无站内消息')" />
        <n-list v-if="items.length" class="notification-list">
          <n-list-item v-for="item in items" :key="item.id">
            <div class="flex flex-wrap items-center gap-8">
              <strong class="break-words">{{ item.title }}</strong>
              <n-tag v-if="!item.readAt" size="small" type="info">
                {{ t('未读') }}
              </n-tag>
            </div>
            <p v-if="item.summary" class="mb-4 mt-6 whitespace-pre-line break-words">
              {{ item.summary }}
            </p>
            <div class="flex flex-wrap items-center justify-between gap-x-12">
              <n-text depth="3">
                {{ formatDateTime(item.createdAt) }}
              </n-text>
              <n-space :size="12">
                <RouterLink v-if="isAppPath(item.targetUrl)" :to="item.targetUrl" @click="show = false">
                  {{ t('查看详情') }}
                </RouterLink>
                <n-button v-if="!item.readAt" text :loading="marking.has(item.id)" @click="markRead(item.id)">
                  {{ t('标记已读') }}
                </n-button>
              </n-space>
            </div>
          </n-list-item>
        </n-list>
        <div v-if="nextBeforeId !== null && !failed" class="mt-12 flex justify-center">
          <n-button size="small" :loading="loading" @click="loadPage(nextBeforeId ?? undefined)">
            {{ t('加载更多') }}
          </n-button>
        </div>
      </n-spin>
    </section>
  </n-popover>
</template>

<script setup lang="ts">
import type { InAppMessage } from '@/api/in-app-messages/api'
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import api from '@/api/in-app-messages/api'
import { t } from '@/i18n'
import { useUserStore } from '@/store'
import { formatDateTime } from '@/utils'

const user = useUserStore()
const show = ref(false)
const unreadOnly = ref(false)
const count = ref(0)
const items = ref<InAppMessage[]>([])
const nextBeforeId = ref<number | null>(null)
const loading = ref(false)
const failed = ref(false)
const markFailed = ref(false)
const marking = reactive(new Map<number, AbortController>())
let countRequest: AbortController | undefined
let pageRequest: AbortController | undefined

function isAppPath(path: string | null): path is string {
  return !!path?.startsWith('/') && !path.startsWith('//') && !path.includes('\\')
}

async function refreshCount() {
  countRequest?.abort()
  const controller = new AbortController()
  countRequest = controller
  try {
    const { data } = await api.unreadCount(controller.signal)
    if (!controller.signal.aborted)
      count.value = data.count
  }
  catch {
    // 短暂失败时保留最近一次的未读数，下次打开时重试。
  }
}

async function loadPage(beforeId?: number) {
  pageRequest?.abort()
  const controller = new AbortController()
  pageRequest = controller
  if (beforeId === undefined) {
    items.value = []
    nextBeforeId.value = null
  }
  loading.value = true
  failed.value = false
  try {
    const { data } = await api.list({ unread: unreadOnly.value || undefined, pageSize: 20, beforeId }, controller.signal)
    if (!controller.signal.aborted) {
      items.value = beforeId === undefined
        ? data.items
        : [
            ...items.value,
            ...data.items,
          ]
      nextBeforeId.value = data.nextBeforeId
    }
  }
  catch {
    if (!controller.signal.aborted)
      failed.value = true
  }
  finally {
    if (!controller.signal.aborted)
      loading.value = false
  }
}

async function markRead(id: number) {
  if (marking.has(id))
    return
  const controller = new AbortController()
  marking.set(id, controller)
  markFailed.value = false
  try {
    await api.markRead(id, controller.signal)
    if (controller.signal.aborted)
      return
    void refreshCount()
    if (show.value && loading.value) {
      // 筛选切换后，尚在途的列表可能仍含标记前的状态。
      void loadPage()
    }
    else if (unreadOnly.value) {
      items.value = items.value.filter(item => item.id !== id)
    }
    else {
      items.value = items.value.map(item => item.id === id
        ? {
            ...item,
            readAt: new Date().toISOString(),
          }
        : item)
    }
  }
  catch {
    if (!controller.signal.aborted)
      markFailed.value = true
  }
  finally {
    if (!controller.signal.aborted)
      marking.delete(id)
  }
}

function cancelRequests() {
  countRequest?.abort()
  pageRequest?.abort()
  for (const controller of marking.values())
    controller.abort()
  marking.clear()
}

watch(() => user.userId, (id) => {
  cancelRequests()
  show.value = false
  count.value = 0
  items.value = []
  nextBeforeId.value = null
  if (id != null)
    void refreshCount()
}, { immediate: true })

watch(show, (open) => {
  if (open) {
    markFailed.value = false
    void refreshCount()
    void loadPage()
  }
  else {
    pageRequest?.abort()
    loading.value = false
  }
})

watch(unreadOnly, () => {
  if (show.value)
    void loadPage()
})

onBeforeUnmount(cancelRequests)
</script>

<style scoped>
.notification-trigger {
  min-inline-size: 32px;
  min-block-size: 32px;
}

.notification-popover {
  width: min(380px, calc(100vw - 44px));
}

.notification-list {
  max-height: min(54dvh, 480px);
  overflow-y: auto;
  overflow-wrap: anywhere;
}
</style>
