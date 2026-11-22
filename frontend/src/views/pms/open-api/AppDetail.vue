<template>
  <div class="app-detail h-full flex flex-col">
    <n-alert v-if="failed" type="error" class="mb-16">
      {{ t('应用详情加载失败') }}
      <n-button text @click="reload">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <n-spin v-if="!app && !failed" :show="loading" class="min-h-60" />
    <template v-if="app">
      <div class="mb-18 flex flex-wrap items-center justify-between gap-12">
        <div class="flex items-center gap-12">
          <h3 class="break-words text-20 font-semibold">
            {{ app.name }}
          </h3>
          <n-tag :type="app.enabled ? 'success' : 'default'" size="small">
            {{ t(app.enabled ? '启用' : '停用') }}
          </n-tag>
        </div>
        <n-space v-if="can.apps">
          <n-button :disabled="loading" @click="emit('edit', app)">
            {{ t('编辑') }}
          </n-button>
          <n-button :type="app.enabled ? 'error' : 'primary'" secondary :disabled="loading" @click="emit('changeStatus', app)">
            {{ t(app.enabled ? '停用' : '启用') }}
          </n-button>
        </n-space>
      </div>
      <n-tabs v-model:value="tab" type="line" class="min-h-0 flex-1">
        <n-tab-pane name="basic" :tab="t('基本信息')">
          <n-descriptions label-placement="left" :column="1" bordered>
            <n-descriptions-item label="AppID">
              <span class="inline-flex items-center gap-8">
                <code class="break-all">{{ app.clientId }}</code>
                <CopyButton :text="app.clientId" :label="t('复制 AppID')" />
              </span>
            </n-descriptions-item>
            <n-descriptions-item :label="t('环境')">
              {{ app.environment }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('负责人')">
              {{ app.ownerDisplayName || '—' }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('用途')">
              <span class="whitespace-pre-wrap break-words">{{ app.description || '—' }}</span>
            </n-descriptions-item>
            <n-descriptions-item :label="t('应用配额')">
              {{ t('{0} 次 / {1} 秒', app.rateLimitCount, app.rateLimitWindowSeconds) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('创建时间')">
              {{ businessTime(app.createdAt) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('更新时间')">
              {{ businessTime(app.updatedAt) }}
            </n-descriptions-item>
          </n-descriptions>
        </n-tab-pane>
        <n-tab-pane name="grants" :tab="t('接口授权')" display-directive="show:lazy">
          <GrantList :app-id="appId" :can-manage="can.grants" @changed="grantsRevision++" />
        </n-tab-pane>
        <n-tab-pane v-if="can.secrets" name="secrets" :tab="t('凭据管理')">
          <SecretList :app="app" @show-grants="tab = 'grants'" />
        </n-tab-pane>
        <n-tab-pane v-if="can.calls" name="calls" :tab="t('调用记录')" display-directive="show:lazy">
          <CallList :app-id="appId" :grants-revision="grantsRevision" />
        </n-tab-pane>
      </n-tabs>
    </template>
  </div>
</template>

<script setup lang="ts">
import type { openApiPermissions } from './model'
import type { App } from '@/api/open-api/api'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import api from '@/api/open-api/api'
import CopyButton from '@/components/common/CopyButton.vue'
import { t } from '@/i18n'
import CallList from './CallList.vue'
import GrantList from './GrantList.vue'
import { businessTime } from './model'
import SecretList from './SecretList.vue'

const props = defineProps<{ appId: number, can: ReturnType<typeof openApiPermissions> }>()
const emit = defineEmits<{ edit: [app: App], changeStatus: [app: App] }>()
const tab = ref('basic')
const app = ref<App>()
const loading = ref(false)
const failed = ref(false)
const grantsRevision = ref(0)
let detailRequest: AbortController | undefined

function apply(record: App) {
  detailRequest?.abort()
  app.value = record
  loading.value = false
  failed.value = false
}

async function reload() {
  const request = new AbortController()
  detailRequest?.abort()
  detailRequest = request
  loading.value = true
  failed.value = false
  try {
    const { data } = await api.app(props.appId, request.signal)
    if (!request.signal.aborted)
      app.value = data
  }
  catch {
    if (!request.signal.aborted)
      failed.value = true
  }
  finally {
    if (!request.signal.aborted)
      loading.value = false
  }
}

watch(() => props.can, (can) => {
  if ((tab.value === 'secrets' && !can.secrets) || (tab.value === 'calls' && !can.calls))
    tab.value = 'basic'
})

onMounted(reload)
onBeforeUnmount(() => detailRequest?.abort())
defineExpose({ apply, reload })
</script>

<style scoped>
.app-detail {
  min-height: 420px;
}
.app-detail :deep(.n-tab-pane) {
  flex: 1;
  min-height: 0;
}
</style>
