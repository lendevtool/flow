<template>
  <n-drawer
    :show="true"
    width="min(780px, 100vw)"
    :aria-label="t('接口详情')"
    :mask-closable="!writing"
    :close-on-esc="!writing"
    @update:show="!$event && close()"
  >
    <n-drawer-content :title="endpoint.displayName" :closable="!writing">
      <n-alert v-if="detailFailed" type="error" class="mb-12">
        {{ t('接口详情加载失败') }}
        <n-button text @click="reloadEndpoint">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-tabs v-model:value="tab" type="line">
        <n-tab-pane name="information" :tab="t('基本信息')">
          <n-alert v-if="contractChanged" type="warning" class="mb-12">
            {{ t(endpoint.status === 'OFFLINE'
              ? '来源契约已变更。重新确认兼容性后，可以再次发布。'
              : '来源契约已变更。请先下线，再重新确认兼容性。') }}
          </n-alert>
          <n-descriptions label-placement="left" :column="1" bordered>
            <n-descriptions-item label="Scope">
              <code class="break-words">{{ endpoint.endpointCode }}</code>
            </n-descriptions-item>
            <n-descriptions-item :label="t('请求路径')">
              <code class="break-words">{{ endpoint.httpMethod }} {{ endpoint.pathTemplate }}</code>
            </n-descriptions-item>
            <n-descriptions-item v-if="endpoint.source" :label="t('来源接口')">
              <code class="break-words">{{ endpoint.httpMethod }} {{ endpoint.source.sourcePath }}</code>
            </n-descriptions-item>
            <n-descriptions-item :label="t('API 版本')">
              {{ endpoint.apiVersion }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('状态')">
              <NTag size="small" :type="endpointStatusType[endpoint.status]">
                {{ endpointStatusLabel(endpoint.status) }}
              </NTag>
            </n-descriptions-item>
            <n-descriptions-item :label="t('授权应用数')">
              {{ endpoint.authorizedAppCount }}
            </n-descriptions-item>
            <n-descriptions-item v-if="endpoint.status === 'PUBLISHED' && !contractChanged && endpoint.documentUrl" :label="t('对外文档')">
              <DocsDownloadButton text :loading="downloading" @download="emit('download', $event)" />
            </n-descriptions-item>
          </n-descriptions>
          <EndpointContract
            v-if="endpoint.source"
            :parameters="endpoint.source.parameters"
            :fields="endpoint.source.fields"
            :collection="endpoint.source.collection"
            :page="endpoint.source.page"
            :whole-response="endpoint.source.wholeResponse"
            class="mt-12"
          />
        </n-tab-pane>
        <n-tab-pane name="apps" :tab="t('已授权应用')" display-directive="show:lazy">
          <div class="h-450 flex flex-col">
            <n-alert v-if="canPublish && endpoint.status === 'PUBLISHED'" type="warning" class="mb-12 shrink-0">
              {{ t('下线后，以下应用将无法调用该接口。现有授权将保留，重新发布后可恢复使用。') }}
            </n-alert>
            <n-alert v-if="appsFailed" type="error" class="mb-12 shrink-0">
              {{ t('加载失败') }}
              <n-button text @click="appsTable?.handleSearch(true)">
                {{ t('重试') }}
              </n-button>
            </n-alert>
            <MeCrud
              ref="appsTable"
              :columns="columns"
              :page-size="20"
              :scroll-x="680"
              :get-data="loadApps"
              class="min-h-0 flex-1"
              @update:query-failed="appsFailed = $event"
            />
          </div>
        </n-tab-pane>
      </n-tabs>
      <template #footer>
        <n-space>
          <n-button :disabled="writing" @click="close">
            {{ t('关闭') }}
          </n-button>
          <n-button
            v-if="canPublish && contractChanged && endpoint.status === 'OFFLINE'"
            type="primary"
            :loading="writing"
            :disabled="loading || detailFailed"
            @click="confirmSource"
          >
            {{ t('重新确认契约') }}
          </n-button>
          <n-button
            v-else-if="canPublish"
            :type="takeOffline ? 'error' : 'primary'"
            :loading="writing"
            :disabled="loading || detailFailed || (endpoint.status === 'PUBLISHED' && tab === 'apps' && !appsReady)"
            @click="changeStatus"
          >
            {{ t(takeOffline ? statusActionLabel : '发布') }}
          </n-button>
        </n-space>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { DocumentFormat } from './documentDownload'
import type { App, Endpoint, PageQuery } from '@/api/open-api/api'
import { NTag } from 'naive-ui'
import { computed, h, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import api from '@/api/open-api/api'
import MeCrud from '@/components/me/crud/index.vue'
import { t } from '@/i18n'
import DocsDownloadButton from './DocsDownloadButton.vue'
import EndpointContract from './EndpointContract.vue'
import { endpointStatusLabel, endpointStatusType, isConflict } from './model'

const props = defineProps<{ initial: Endpoint, canPublish: boolean, downloading: boolean, inspectImpact: boolean }>()
const emit = defineEmits<{ close: [], changed: [], download: [format: DocumentFormat] }>()
const endpoint = ref(props.initial)
const tab = ref(props.inspectImpact ? 'apps' : 'information')
const appsTable = ref<InstanceType<typeof MeCrud>>()
const appsFailed = ref(false)
const appsReady = ref(false)
let appsQueryId = 0
const detailFailed = ref(false)
const loading = ref(false)
const writing = ref(false)
let detailRequest: AbortController | undefined
const contractChanged = computed(() => endpoint.value.source?.contractMatches === false)
const takeOffline = computed(() => endpoint.value.status === 'PUBLISHED'
  || (contractChanged.value && endpoint.value.status === 'REGISTERED'))
const statusActionLabel = computed(() => endpoint.value.status === 'PUBLISHED' && tab.value !== 'apps'
  ? '查看下线影响'
  : '下线')
const columns = computed<DataTableColumns<App>>(() => [
  {
    title: t('应用名称'),
    key: 'name',
    minWidth: 145,
    ellipsis: { tooltip: true },
  },
  {
    title: 'AppID',
    key: 'clientId',
    width: 225,
    ellipsis: { tooltip: true },
  },
  {
    title: t('环境'),
    key: 'environment',
    width: 100,
    ellipsis: { tooltip: true },
  },
  {
    title: t('负责人'),
    key: 'ownerDisplayName',
    minWidth: 125,
    ellipsis: { tooltip: true },
    render: row => row.ownerDisplayName || '—',
  },
  {
    title: t('状态'),
    key: 'enabled',
    width: 83,
    render: row => h(NTag, {
      size: 'small',
      type: row.enabled ? 'success' : 'default',
    }, () => t(row.enabled ? '启用' : '停用')),
  },
])

async function reloadEndpoint() {
  const request = new AbortController()
  detailRequest?.abort()
  detailRequest = request
  detailFailed.value = false
  loading.value = true
  try {
    const { data } = await api.endpoint(props.initial.id, request.signal)
    if (!request.signal.aborted)
      endpoint.value = data
  }
  catch {
    if (!request.signal.aborted)
      detailFailed.value = true
  }
  finally {
    if (!request.signal.aborted)
      loading.value = false
  }
}

async function loadApps(page: PageQuery) {
  const id = ++appsQueryId
  appsReady.value = false
  const response = await api.endpointApps(props.initial.id, page)
  if (id === appsQueryId)
    appsReady.value = true
  return response
}

function close() {
  if (!writing.value)
    emit('close')
}

function changeStatus() {
  const current = endpoint.value
  const offline = takeOffline.value
  if (current.status === 'PUBLISHED' && tab.value !== 'apps') {
    tab.value = 'apps'
    return
  }
  window.$dialog.warning({
    title: t(offline ? '下线接口' : '发布接口'),
    content: offline
      ? t('下线「{0}」？当前获得授权的 {1} 个应用将无法调用该接口。', current.displayName, current.authorizedAppCount)
      : t('发布「{0}」？当前仍有效的授权将在发布后恢复。', current.displayName),
    positiveText: t(offline ? '下线' : '发布'),
    negativeText: t('取消'),
    onPositiveClick: () => updateEndpoint(() => api.endpointStatus(current.id, offline ? 'OFFLINE' : 'PUBLISHED', current.version)),
  })
}

function confirmSource() {
  const current = endpoint.value
  window.$dialog.warning({
    title: t('重新确认契约'),
    content: t('系统将校验当前来源能否兼容已发布的公开契约。确认后可重新发布。'),
    positiveText: t('确认'),
    negativeText: t('取消'),
    onPositiveClick: () => updateEndpoint(() => api.confirmEndpointSource(current.id, current.version)),
  })
}

async function updateEndpoint(action: () => Promise<{ data: Endpoint }>) {
  if (writing.value)
    return false
  writing.value = true
  try {
    const { data } = await action()
    endpoint.value = data
    emit('changed')
    window.$message.success(t('操作成功'))
    return true
  }
  catch (error) {
    if (isConflict(error)) {
      void reloadEndpoint()
      void appsTable.value?.handleSearch(true)
      emit('changed')
      return true
    }
    return false
  }
  finally {
    writing.value = false
  }
}

watch(appsTable, table => table?.handleSearch())
onMounted(reloadEndpoint)
onBeforeUnmount(() => detailRequest?.abort())
</script>
