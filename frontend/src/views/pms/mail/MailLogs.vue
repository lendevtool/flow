<template>
  <div class="h-full flex flex-col">
    <n-alert v-if="listFailed || optionsFailed" type="error" class="mb-12 shrink-0">
      {{ t('加载失败') }}
      <n-button text @click="reload">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <MeCrud
      ref="table"
      v-model:query-items="filters"
      :columns="columns"
      :get-data="loadLogs"
      :page-size="20"
      :scroll-x="1260"
      :query-disabled="rangeError"
      class="min-h-0 flex-1"
      @update:query-failed="listFailed = $event"
    >
      <MeQueryItem :label="t('发件邮箱')" :label-width="76" :content-width="176">
        <n-select v-model:value="filters.accountId" :options="accountOptions" :loading="optionsLoading" filterable clearable />
      </MeQueryItem>
      <MeQueryItem :label="t('邮件模板')" :label-width="76" :content-width="176">
        <n-select v-model:value="filters.templateId" :options="templateOptions" :loading="optionsLoading" filterable clearable />
      </MeQueryItem>
      <MeQueryItem :label="t('状态')" :label-width="50" :content-width="140">
        <n-select v-model:value="filters.status" :options="statusOptions()" clearable />
      </MeQueryItem>
      <DateRangeFilter v-model="filters.range" :label="t('提交日期')" class="max-w-full w-495" />
    </MeCrud>
    <LogDrawer v-if="selected !== null" :id="selected" @close="selected = null" />
  </div>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { LogOptions, MailLog, MailStatus } from './api'
import type { DateRange } from '@/utils/businessDate'
import { computed, h, onMounted, ref } from 'vue'
import DateRangeFilter from '@/components/common/DateRangeFilter.vue'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import MeQueryItem from '@/components/me/crud/QueryItem.vue'
import { t } from '@/i18n'
import { isDateRangeReversed, shiftDays } from '@/utils/businessDate'
import api from './api'
import LogDrawer from './LogDrawer.vue'
import { logTime, sendModeLabels, statusOptions, statusTag } from './presentation'

interface Filters {
  accountId: number | null
  templateId: number | null
  status: MailStatus | null
  range: DateRange
}

const table = ref<InstanceType<typeof MeCrud> | null>(null)
const filters = ref<Filters>({
  accountId: null,
  templateId: null,
  status: null,
  range: null,
})
const rangeError = computed(() => isDateRangeReversed(filters.value.range))
const listFailed = ref(false)
const optionsFailed = ref(false)
const optionsLoading = ref(false)
const options = ref<LogOptions>({ accounts: [], templates: [] })
const selected = ref<number | null>(null)
const accountOptions = computed(() => options.value.accounts.map(row => ({ label: `${row.name} · ${row.code}`, value: row.id })))
const templateOptions = computed(() => options.value.templates.map(row => ({ label: `${row.name} · ${row.code}`, value: row.id })))

const columns = computed<DataTableColumns<MailLog>>(() => [
  {
    title: t('提交时间'),
    key: 'createdAt',
    width: 163,
    render: row => logTime(row.createdAt),
  },
  {
    title: t('发件地址'),
    key: 'senderAddress',
    minWidth: 190,
    ellipsis: { tooltip: true },
  },
  {
    title: t('模板编码'),
    key: 'templateCode',
    minWidth: 150,
    ellipsis: { tooltip: true },
  },
  {
    title: t('版本'),
    key: 'templateRevision',
    width: 80,
  },
  {
    title: t('发送方式'),
    key: 'sendMode',
    width: 120,
    render: row => t(sendModeLabels[row.sendMode]),
  },
  {
    title: t('收件人数'),
    key: 'recipientCount',
    width: 108,
  },
  {
    title: t('状态'),
    key: 'status',
    width: 112,
    render: row => statusTag(row.status),
  },
  {
    title: t('操作'),
    key: 'actions',
    width: 105,
    align: 'right',
    fixed: 'right',
    render: row => h(TableActions, {
      actions: [
        {
          key: 'details',
          label: t('详情'),
          onClick: () => selected.value = row.id,
        },
      ],
    }),
  },
])

function loadLogs({ range, ...params }: Filters & { pageNo: number, pageSize: number }) {
  const [from, to] = range ?? []
  return api.logs({
    ...params,
    from: from
      ? new Date(`${from}T00:00:00+08:00`).toISOString()
      : undefined,
    to: to
      ? new Date(`${shiftDays(to, 1)}T00:00:00+08:00`).toISOString()
      : undefined,
  })
}

async function loadOptions() {
  if (optionsLoading.value)
    return
  optionsFailed.value = false
  optionsLoading.value = true
  try {
    options.value = (await api.logOptions()).data
  }
  catch {
    optionsFailed.value = true
  }
  finally {
    optionsLoading.value = false
  }
}

function reload() {
  void loadOptions()
  void table.value?.handleSearch()
}

onMounted(reload)
</script>
