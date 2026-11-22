<template>
  <n-alert type="info" :show-icon="false" class="mb-16">
    {{ t('重试会重新处理这条审计事件。默认显示等待重试与需要人工处理的记录。') }}
  </n-alert>
  <div class="mb-16 flex flex-wrap items-center gap-12">
    <n-select v-model:value="queueStatus" :options="states" clearable :placeholder="t('等待重试 / 需人工处理')" class="w-240" @update:value="search" />
    <NButton secondary :loading="loading" @click="load">
      {{ t('刷新状态') }}
    </NButton>
    <span class="text-12 opacity-50">{{ t('{0} 条记录', total) }}</span>
  </div>
  <n-alert v-if="error" type="error" class="mb-12">
    {{ error }}
  </n-alert>
  <n-data-table :columns="columns" :data="rows" :loading="loading" :pagination="false" :row-key="row => row.id" :scroll-x="1300" :single-line="false" :size="appStore.tableSize" />
  <div class="mt-16 flex justify-end">
    <n-pagination v-model:page="page" :page-size="20" :item-count="total" @update:page="load" />
  </div>
</template>

<script setup>
import { NButton, NTag } from 'naive-ui'
import { onBeforeUnmount } from 'vue'
import TableActions from '@/components/common/TableActions.vue'
import { t } from '@/i18n'
import { useAppStore } from '@/store'
import api from './api'
import { queueInfo, timeText } from './format'

const props = defineProps({
  operations: {
    type: Array,
    default: () => [],
  },
})
const emit = defineEmits(['retried'])
const appStore = useAppStore()
const rows = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const error = ref('')
const queueStatus = ref(null)
const retrying = ref(null)
let generation = 0
const states = computed(() => [
  'RETRY',
  'DEAD',
  'PREPARED',
  'PENDING',
  'PROCESSING',
  'DONE',
].map(value => ({ value, label: queueInfo(value).label })))
const columns = computed(() => [
  {
    title: t('操作'),
    key: 'operationCode',
    width: 180,
    render: row => props.operations.find(item => item.value === row.operationCode)?.label || row.operationCode,
  },
  {
    title: t('状态'),
    key: 'status',
    width: 145,
    render: row => h(NTag, { type: queueInfo(row.status).type, size: 'small', bordered: false }, () => queueInfo(row.status).label),
  },
  {
    title: t('创建时间'),
    key: 'createdAt',
    width: 175,
    render: row => timeText(row.createdAt),
  },
  {
    title: t('累计投递 / 本轮失败'),
    key: 'attempts',
    width: 155,
    render: row => `${row.attempts} / ${row.failureCount}`,
  },
  {
    title: t('下次处理时间'),
    key: 'nextRetryAt',
    width: 175,
    render: row => timeText(row.nextRetryAt || row.prepareDeadlineAt || row.lockedUntil),
  },
  {
    title: t('最近错误'),
    key: 'lastErrorMessage',
    minWidth: 210,
    render: row => row.lastErrorMessage || '—',
  },
  {
    title: t('事件 ID'),
    key: 'eventId',
    width: 250,
    ellipsis: { tooltip: true },
  },
  {
    title: t('操作'),
    key: 'actions',
    width: 110,
    fixed: 'right',
    render: row => h(TableActions, {
      actions: [
        {
          key: 'retry',
          label: t('重试投递'),
          secondary: true,
          type: 'primary',
          loading: retrying.value === row.id,
          disabled: !['RETRY', 'DEAD'].includes(row.status) || retrying.value !== null,
          onClick: () => $dialog.confirm({
            title: t('重试审计投递'),
            content: t('确认重新处理此事件并更新投递状态？'),
            confirm: () => retry(row),
          }),
        },
      ],
    }),
  },
])

async function load() {
  const own = ++generation
  loading.value = true
  error.value = ''
  try {
    const { data } = await api.queue({ pageNo: page.value, pageSize: 20, status: queueStatus.value || undefined })
    if (own === generation) {
      rows.value = data.pageData
      total.value = data.total
    }
  }
  catch (failure) {
    if (own === generation) {
      rows.value = []
      error.value = failure?.message || t('加载投递状态失败')
    }
  }
  finally {
    if (own === generation)
      loading.value = false
  }
}
function search() {
  page.value = 1
  load()
}
async function retry(row) {
  retrying.value = row.id
  error.value = ''
  try {
    await api.retry(row.id, row.rowVersion)
    window.$message?.success(t('已安排重新投递'))
    await load()
    emit('retried')
  }
  catch (failure) {
    error.value = failure?.message || t('安排重试失败，请刷新后重试')
  }
  finally {
    retrying.value = null
  }
}
onMounted(load)
onBeforeUnmount(() => generation++)
defineExpose({ load })
</script>
