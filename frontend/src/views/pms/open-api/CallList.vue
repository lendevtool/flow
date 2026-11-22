<template>
  <div class="h-full flex flex-col">
    <n-alert v-if="failed" type="error" class="mb-12 shrink-0">
      {{ t('调用记录加载失败') }}
      <n-button text @click="reload">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <n-alert v-if="endpointFailed" type="error" class="mb-12 shrink-0">
      {{ t('接口筛选选项加载失败') }}
      <n-button text @click="loadEndpoints">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <n-text v-if="invalidRange && !reversed" type="warning" role="alert" class="mb-12 shrink-0">
      {{ t('调用记录单次查询最多 31 天。') }}
    </n-text>
    <MeCrud
      ref="table"
      v-model:query-items="filters"
      :columns="columns"
      :page-size="20"
      :scroll-x="1160"
      :get-data="loadCalls"
      :query-disabled="invalidRange"
      class="min-h-0 flex-1"
      @update:query-failed="failed = $event"
    >
      <DateRangeFilter v-model="filters.dates" :label="t('调用日期')" style="width: min(560px, 68vw)" />
      <MeQueryItem :label="t('接口')" :label-width="45" :content-width="220">
        <n-select v-model:value="filters.endpointId" :options="endpointOptions" :loading="endpointLoading" clearable filterable />
      </MeQueryItem>
      <MeQueryItem :label="t('请求 ID')" :label-width="62" :content-width="205">
        <n-input v-model:value="filters.requestId" clearable :maxlength="64" />
      </MeQueryItem>
      <MeQueryItem :label="t('调用结果')" :label-width="68" :content-width="112">
        <n-select v-model:value="filters.result" :options="resultOptions" clearable />
      </MeQueryItem>
    </MeCrud>
  </div>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { CallRecord, CallResult, Grant, PageQuery } from '@/api/open-api/api'
import type { DateRange } from '@/utils/businessDate'
import { NTag } from 'naive-ui'
import { computed, h, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import api from '@/api/open-api/api'
import DateRangeFilter from '@/components/common/DateRangeFilter.vue'
import MeCrud from '@/components/me/crud/index.vue'
import MeQueryItem from '@/components/me/crud/QueryItem.vue'
import { t } from '@/i18n'
import { isDateRangeReversed } from '@/utils/businessDate'
import { businessTime, callTimeRange } from './model'

const props = defineProps<{ appId: number, grantsRevision: number }>()
interface Filters {
  dates: DateRange
  endpointId: number | null
  requestId: string | null
  result: CallResult | null
}
const table = ref<InstanceType<typeof MeCrud>>()
const filters = ref<Filters>({
  dates: null,
  endpointId: null,
  requestId: null,
  result: null,
})
const failed = ref(false)
const endpointFailed = ref(false)
const endpointLoading = ref(false)
const grants = ref<Grant[]>([])
let request: AbortController | undefined

const invalidRange = computed(() => callTimeRange(filters.value.dates) === null)
const reversed = computed(() => isDateRangeReversed(filters.value.dates))
const endpointOptions = computed(() => grants.value.map(grant => ({
  value: grant.endpointId,
  label: `${grant.displayName} · ${grant.endpointCode}`,
})))
const results = {
  SUCCESS: { label: '成功', type: 'success' },
  DENIED: { label: '拒绝', type: 'warning' },
  FAILED: { label: '失败', type: 'error' },
} as const
const resultOptions = computed(() => (Object.keys(results) as CallResult[]).map(value => ({
  value,
  label: t(results[value].label),
})))

const columns = computed<DataTableColumns<CallRecord>>(() => [
  {
    title: t('调用时间'),
    key: 'occurredAt',
    width: 156,
    render: row => businessTime(row.occurredAt),
  },
  {
    title: t('接口'),
    key: 'endpointCode',
    minWidth: 190,
    ellipsis: { tooltip: true },
    render: row => row.endpointCode || '—',
  },
  {
    title: t('请求 ID'),
    key: 'requestId',
    width: 200,
    ellipsis: { tooltip: true },
  },
  {
    title: t('结果'),
    key: 'result',
    width: 86,
    render: row => h(NTag, { size: 'small', type: results[row.result]?.type }, () => results[row.result]
      ? t(results[row.result].label)
      : '—'),
  },
  {
    title: t('HTTP 状态'),
    key: 'httpStatus',
    width: 95,
  },
  {
    title: t('错误码'),
    key: 'errorCode',
    minWidth: 120,
    ellipsis: { tooltip: true },
    render: row => row.errorCode || '—',
  },
  {
    title: t('耗时（毫秒）'),
    key: 'durationMs',
    width: 112,
  },
  {
    title: t('来源 IP'),
    key: 'sourceIp',
    width: 145,
    ellipsis: { tooltip: true },
    render: row => row.sourceIp || '—',
  },
])

function loadCalls({ dates, endpointId, requestId, result, ...page }: Filters & PageQuery) {
  return api.calls(props.appId, {
    ...page,
    ...callTimeRange(dates),
    endpointId: endpointId ?? undefined,
    requestId: requestId?.trim() || undefined,
    result: result ?? undefined,
  })
}

function reload() {
  return table.value?.handleSearch(true)
}

async function loadEndpoints() {
  request?.abort()
  const current = new AbortController()
  request = current
  endpointFailed.value = false
  endpointLoading.value = true
  try {
    const { data } = await api.grants(props.appId, current.signal)
    if (!current.signal.aborted)
      grants.value = data
  }
  catch {
    if (!current.signal.aborted)
      endpointFailed.value = true
  }
  finally {
    if (!current.signal.aborted)
      endpointLoading.value = false
  }
}

watch(() => props.grantsRevision, loadEndpoints)
onMounted(() => {
  void loadEndpoints()
  void reload()
})
onBeforeUnmount(() => request?.abort())
</script>
