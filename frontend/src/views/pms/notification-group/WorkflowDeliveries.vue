<template>
  <div class="min-h-0 flex flex-col flex-1">
    <n-alert v-if="listFailed" type="error" class="mb-12 shrink-0">
      {{ t('加载失败') }}
      <n-button text @click="reload">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <n-alert v-if="sceneCodeInvalid" type="warning" class="mb-12 shrink-0">
      {{ t('请输入有效的场景编码。') }}
    </n-alert>
    <MeCrud
      ref="table"
      v-model:query-items="filters"
      :page-size="PAGE_SIZE"
      :columns="columns"
      :get-data="loadDeliveries"
      :scroll-x="1350"
      :query-disabled="rangeError || sceneCodeInvalid"
      class="min-h-0 flex-1"
      @update:query-failed="listFailed = $event"
    >
      <MeQueryItem :label="t('流程编号')" :label-width="75" :content-width="145">
        <n-input-number v-model:value="filters.flowInstanceId" :min="1" :max="Number.MAX_SAFE_INTEGER" :precision="0" :show-button="false" clearable />
      </MeQueryItem>
      <MeQueryItem :label="t('场景编码')" :label-width="75" :content-width="185">
        <n-input v-model:value="filters.sceneCode" :maxlength="64" clearable :input-props="{ 'aria-label': t('场景编码') }" />
      </MeQueryItem>
      <MeQueryItem :label="t('状态')" :label-width="50" :content-width="130">
        <n-select v-model:value="filters.status" :options="deliveryStatusOptions()" clearable />
      </MeQueryItem>
      <DateRangeFilter v-model="filters.range" :label="t('创建日期')" class="max-w-full w-495" />
      <template #empty="{ loading }">
        <n-empty v-if="!loading && !listFailed" :description="t('没有匹配的流程通知投递')" />
      </template>
    </MeCrud>
    <WorkflowDeliveryDrawer
      v-if="selected !== null"
      :id="selected"
      :can-view-mail-log="canViewMailLog"
      @close="selected = null"
    />
  </div>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { DeliveryRow, DeliveryStatus, Paging } from './api'
import type { DateRange } from '@/utils/businessDate'
import { computed, h, onMounted, ref } from 'vue'
import DateRangeFilter from '@/components/common/DateRangeFilter.vue'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import MeQueryItem from '@/components/me/crud/QueryItem.vue'
import { t } from '@/i18n'
import { isDateRangeReversed, shiftDays } from '@/utils/businessDate'
import api from './api'
import { deliveryKindLabel, deliveryStatusOptions, deliveryStatusTag, deliveryTime } from './deliveryPresentation'
import { PAGE_SIZE, SCENE_CODE } from './model'
import WorkflowDeliveryDrawer from './WorkflowDeliveryDrawer.vue'

defineProps<{ canViewMailLog: boolean }>()

interface Filters {
  flowInstanceId: number | null
  sceneCode: string | null
  status: DeliveryStatus | null
  range: DateRange
}

const table = ref<InstanceType<typeof MeCrud> | null>(null)
const listFailed = ref(false)
const selected = ref<number | null>(null)
const filters = ref<Filters>({
  flowInstanceId: null,
  sceneCode: null,
  status: null,
  range: null,
})
const rangeError = computed(() => isDateRangeReversed(filters.value.range))
const sceneCodeInvalid = computed(() => {
  const code = filters.value.sceneCode?.trim()
  return !!code && !SCENE_CODE.test(code)
})

const columns = computed<DataTableColumns<DeliveryRow>>(() => [
  {
    title: t('投递编号'),
    key: 'id',
    width: 100,
    ellipsis: { tooltip: true },
  },
  {
    title: t('流程编号'),
    key: 'flowInstanceId',
    width: 106,
    ellipsis: { tooltip: true },
    render: row => `WF-${row.flowInstanceId}`,
  },
  {
    title: t('场景编码'),
    key: 'sceneCode',
    minWidth: 170,
    ellipsis: { tooltip: true },
  },
  {
    title: t('投递类型'),
    key: 'kind',
    width: 140,
    ellipsis: { tooltip: true },
    render: row => deliveryKindLabel(row.kind),
  },
  {
    title: t('状态'),
    key: 'status',
    width: 115,
    render: row => deliveryStatusTag(row.status),
  },
  {
    title: t('尝试次数'),
    key: 'attemptCount',
    width: 100,
    align: 'right',
  },
  {
    title: t('新建时间'),
    key: 'createdAt',
    width: 168,
    render: row => deliveryTime(row.createdAt),
  },
  {
    title: t('完成时间'),
    key: 'finishedAt',
    width: 168,
    render: row => deliveryTime(row.finishedAt),
  },
  {
    title: t('原因代码'),
    key: 'lastErrorCode',
    minWidth: 175,
    ellipsis: { tooltip: true },
    render: row => row.lastErrorCode ?? '—',
  },
  {
    title: t('操作'),
    key: 'actions',
    width: 100,
    fixed: 'right',
    align: 'right',
    render: row => h(TableActions, {
      actions: [
        {
          key: 'detail',
          label: t('详情'),
          icon: 'i-fe:eye',
          onClick: () => selected.value = row.id,
        },
      ],
    }),
  },
])

function loadDeliveries({ flowInstanceId, sceneCode, status, range, pageNo, pageSize }: Filters & Paging) {
  const [from, to] = range ?? []
  return api.workflowDeliveries({
    pageNo,
    pageSize,
    flowInstanceId: flowInstanceId ?? undefined,
    sceneCode: sceneCode?.trim() || undefined,
    status: status ?? undefined,
    from: from
      ? new Date(`${from}T00:00:00+08:00`).toISOString()
      : undefined,
    to: to
      ? new Date(`${shiftDays(to, 1)}T00:00:00+08:00`).toISOString()
      : undefined,
  })
}

function reload() {
  void table.value?.handleSearch(true)
}

onMounted(reload)
</script>
