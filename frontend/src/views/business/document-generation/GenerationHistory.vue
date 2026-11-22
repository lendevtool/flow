<template>
  <div class="docgen-history flex flex-col">
    <n-alert v-if="failed" type="error" class="mb-12 shrink-0">
      {{ t('加载失败') }}
      <n-button text @click="table?.handleSearch(true)">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <MeCrud
      ref="table"
      v-model:query-items="filters"
      :columns="columns"
      :get-data="load"
      :page-size="20"
      :scroll-x="880"
      :query-disabled="isDateRangeReversed(filters.date)"
      class="min-h-0 flex-1"
      @update:query-failed="failed = $event"
    >
      <MeQueryItem :label="t('模板')" :content-width="180">
        <n-select v-model:value="filters.templateId" :input-props="{ 'aria-label': t('模板') }" :options="templateOptions" filterable clearable />
      </MeQueryItem>
      <MeQueryItem :label="t('状态')" :content-width="130">
        <n-select v-model:value="filters.status" :input-props="{ 'aria-label': t('状态') }" :options="statusOptions" clearable />
      </MeQueryItem>
      <DateRangeFilter v-model="filters.date" :label="t('生成日期')" class="docgen-history-date" />
      <template #empty="{ loading }">
        <n-empty v-if="!loading && !failed" :description="t('暂无生成记录')" />
      </template>
    </MeCrud>
  </div>
  <n-drawer :show="selected !== null" width="min(520px, 100vw)" @update:show="show => !show && close()">
    <n-drawer-content :title="t('生成记录详情')" closable>
      <n-alert v-if="detailError" type="error" class="mb-12">
        {{ detailError }}
        <n-button text @click="selected && open(selected)">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-spin v-if="selected" :show="loadingDetail">
        <n-descriptions :column="1" bordered label-placement="left">
          <n-descriptions-item :label="t('模板')">
            {{ selected.templateName }}
          </n-descriptions-item>
          <n-descriptions-item :label="t('版本')">
            {{ selected.versionNo }}
          </n-descriptions-item>
          <n-descriptions-item :label="t('状态')">
            {{ statusLabel(selected.status) }}
          </n-descriptions-item>
          <n-descriptions-item :label="t('生成时间')">
            {{ formatDateTime(selected.createdAt) }}
          </n-descriptions-item>
          <n-descriptions-item :label="t('完成时间')">
            {{ selected.finishedAt ? formatDateTime(selected.finishedAt) : '—' }}
          </n-descriptions-item>
          <n-descriptions-item v-if="selected.errorCode" :label="t('失败原因')">
            {{ generationError(selected.errorCode) }}
          </n-descriptions-item>
          <n-descriptions-item v-if="canRun" :label="t('输入快照')">
            {{ t(selected.hasInput ? '可回填' : '输入快照不可用或已过期') }}
          </n-descriptions-item>
        </n-descriptions>
        <n-space class="mt-16">
          <n-button v-if="canRun && selected.hasInput" :loading="restoring === selected.id" @click="emit('restore', selected)">
            {{ t('回填输入') }}
          </n-button>
          <n-button v-if="canReadFile && selected.hasFile" :loading="fileBusy === selected.id" @click="emit('file', selected, 'preview')">
            {{ t('预览') }}
          </n-button>
          <n-button v-if="canReadFile && selected.hasFile" :disabled="fileBusy !== null" @click="emit('file', selected, 'download')">
            {{ t('下载 Word') }}
          </n-button>
          <n-button v-if="selected.status === 'RUNNING'" :loading="loadingDetail" @click="open(selected)">
            {{ t('刷新状态') }}
          </n-button>
        </n-space>
      </n-spin>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { Generation, GenerationStatus, TemplateSummary } from './api'
import type { DateRange } from '@/utils/businessDate'
import { NTag } from 'naive-ui'
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue'
import DateRangeFilter from '@/components/common/DateRangeFilter.vue'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import MeQueryItem from '@/components/me/crud/QueryItem.vue'
import { t } from '@/i18n'
import { formatDateTime } from '@/utils'
import { isDateRangeReversed } from '@/utils/businessDate'
import api, { errorText } from './api'
import { generationError, statusLabel } from './schema'

interface Filters {
  templateId: string | null
  status: GenerationStatus | null
  date: DateRange
}

const props = defineProps<{
  templates: TemplateSummary[]
  canRun: boolean
  canReadFile: boolean
  restoring: string | null
  fileBusy: string | null
}>()
const emit = defineEmits<{ restore: [record: Generation], file: [record: Generation, action: 'preview' | 'download'] }>()
const table = ref<InstanceType<typeof MeCrud>>()
const filters = ref<Filters>({ templateId: null, status: null, date: null })
const knownTemplates = ref(new Map<string, string>())
const failed = ref(false)
const selected = ref<Generation | null>(null)
const loadingDetail = ref(false)
const detailError = ref('')
let detailRequest: AbortController | undefined

const templateOptions = computed(() => {
  const options = new Map(knownTemplates.value)
  for (const item of props.templates)
    options.set(item.id, item.name)
  return Array.from(options, ([value, label]) => ({ value, label }))
})
const statusOptions = computed(() => (['RUNNING', 'SUCCEEDED', 'FAILED'] as const).map(value => ({
  value,
  label: statusLabel(value),
})))
const columns = computed<DataTableColumns<Generation>>(() => [
  {
    key: 'templateName',
    title: t('模板'),
    minWidth: 200,
    ellipsis: { tooltip: true },
  },
  {
    key: 'versionNo',
    title: t('版本'),
    width: 82,
  },
  {
    key: 'status',
    title: t('状态'),
    width: 112,
    render: row => h(NTag, {
      size: 'small',
      type: row.status === 'SUCCEEDED'
        ? 'success'
        : row.status === 'FAILED'
          ? 'error'
          : 'info',
    }, () => statusLabel(row.status)),
  },
  {
    key: 'createdAt',
    title: t('生成时间'),
    width: 184,
    render: row => formatDateTime(row.createdAt),
  },
  {
    key: 'actions',
    title: t('操作'),
    width: 300,
    fixed: 'right',
    render: row => h(TableActions, {
      actions: [
        {
          key: 'detail',
          label: t('详情'),
          onClick: () => open(row),
        },
        ...(props.canRun && row.hasInput
          ? [
              {
                key: 'restore',
                label: t('回填输入'),
                disabled: props.restoring !== null,
                onClick: () => emit('restore', row),
              },
            ]
          : []),
        ...(props.canReadFile && row.hasFile
          ? [
              {
                key: 'preview',
                label: t('预览'),
                disabled: props.fileBusy !== null,
                onClick: () => emit('file', row, 'preview'),
              },
              {
                key: 'download',
                label: t('下载'),
                disabled: props.fileBusy !== null,
                onClick: () => emit('file', row, 'download'),
              },
            ]
          : []),
      ],
    }),
  },
])

async function load({ pageNo, pageSize, templateId, status, date }: Filters & { pageNo: number, pageSize: number }) {
  try {
    const { data } = await api.generations({
      pageNo,
      pageSize,
      templateId: templateId ?? undefined,
      status: status ?? undefined,
      startDate: date?.[0] ?? undefined,
      endDate: date?.[1] ?? undefined,
    })
    for (const record of data.records)
      knownTemplates.value.set(record.templateId, record.templateName)
    return {
      data: { pageData: data.records, total: data.total },
    }
  }
  catch {
    throw new Error(t('生成记录加载失败'))
  }
}

async function open(row: Generation) {
  detailRequest?.abort()
  const controller = new AbortController()
  detailRequest = controller
  selected.value = row
  loadingDetail.value = true
  detailError.value = ''
  try {
    const { data } = await api.generation(row.id, controller.signal)
    if (!controller.signal.aborted)
      selected.value = data
  }
  catch (error) {
    if (!controller.signal.aborted)
      detailError.value = errorText(error, t('加载失败'))
  }
  finally {
    if (!controller.signal.aborted)
      loadingDetail.value = false
  }
}

function close() {
  detailRequest?.abort()
  selected.value = null
}

function refresh() {
  return table.value?.handleSearch(true)
}

onMounted(refresh)
onBeforeUnmount(close)
defineExpose({ refresh, close })
</script>

<style scoped>
.docgen-history {
  height: min(560px, 70vh);
}

.docgen-history-date {
  width: min(520px, 70vw);
}
</style>
