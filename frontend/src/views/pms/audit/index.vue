<template>
  <CommonPage>
    <template #action>
      <div class="flex gap-10">
        <NButton v-if="can('AuditExport') && tab === 'logs'" secondary :loading="exporting" @click="exportLogs">
          <i class="i-fe:download mr-6" />{{ t('导出当前筛选') }}
        </NButton>
        <NButton secondary :loading="loading" @click="refresh">
          <i class="i-fe:refresh-cw mr-6" />{{ t('刷新') }}
        </NButton>
      </div>
    </template>
    <n-alert :type="counts.DEAD ? 'warning' : 'info'" :show-icon="false" class="mb-20">
      <template v-if="pending || counts.DEAD">
        {{ t('{0} 条操作等待整理，{1} 条需人工处理。', pending, counts.DEAD || 0) }}
        <span v-if="oldest">{{ t('最早待处理：{0}。', timeText(oldest)) }}</span>
      </template>
      <template v-else>
        {{ t('已提交的操作会在后台整理完成后显示。') }}
      </template>
    </n-alert>
    <n-tabs v-model:value="tab" type="line" @update:value="tabChanged">
      <n-tab-pane name="logs" :tab="t('操作记录')">
        <n-form label-placement="top" size="small" @submit.prevent="search">
          <div class="audit-filters">
            <n-form-item :label="t('操作时间（默认最近7天）')">
              <n-date-picker v-model:value="filters.range" type="datetimerange" clearable class="w-full" />
            </n-form-item>
            <n-form-item :label="t('操作者 / 声称账号')">
              <n-input v-model:value="filters.actorKeyword" :placeholder="t('姓名或账号')" clearable :maxlength="100" @keyup.enter="search" />
            </n-form-item>
            <n-form-item :label="t('模块')">
              <n-select v-model:value="filters.moduleCode" :options="modules" clearable :placeholder="t('全部模块')" />
            </n-form-item>
            <n-form-item :label="t('结果')">
              <n-select v-model:value="filters.result" :options="results" clearable :placeholder="t('全部结果')" />
            </n-form-item>
          </div>
          <n-collapse-transition :show="advanced">
            <div class="audit-more-filters">
              <n-form-item :label="t('具体操作')">
                <n-select v-model:value="filters.operationCode" :options="operationOptions" filterable clearable :placeholder="t('全部操作')" />
              </n-form-item>
              <n-form-item :label="t('对象类型')">
                <n-select v-model:value="filters.objectType" :options="objectTypes" filterable tag clearable :placeholder="t('选择或输入类型编码')" />
              </n-form-item>
              <n-form-item :label="t('对象 ID')">
                <n-input v-model:value="filters.objectId" :maxlength="128" clearable :placeholder="t('精确匹配')" @keyup.enter="search" />
              </n-form-item>
              <n-form-item :label="t('对象编码')">
                <n-input v-model:value="filters.objectCode" :maxlength="128" clearable :placeholder="t('精确匹配')" @keyup.enter="search" />
              </n-form-item>
              <n-form-item :label="t('所属参数分类 ID')">
                <n-input v-model:value="parentGroup" :maxlength="128" clearable :placeholder="t('查询分类下明细的历史')" @keyup.enter="search" />
              </n-form-item>
              <n-form-item :label="t('操作者 ID')">
                <n-input v-model:value="filters.actorId" :maxlength="128" clearable :placeholder="t('按历史用户标识查询')" @keyup.enter="search" />
              </n-form-item>
              <n-form-item :label="t('请求 ID')">
                <n-input v-model:value="filters.requestId" :maxlength="64" clearable :placeholder="t('关联同一请求')" @keyup.enter="search" />
              </n-form-item>
              <n-form-item :label="t('同次操作 ID')">
                <n-input v-model:value="filters.operationId" :maxlength="36" clearable :placeholder="t('关联同一操作的不同阶段')" @keyup.enter="search" />
              </n-form-item>
            </div>
          </n-collapse-transition>
          <div class="mb-20 flex flex-wrap items-center gap-12">
            <NButton type="primary" :loading="loading" @click="search">
              <i class="i-fe:search mr-6" />{{ t('查询') }}
            </NButton>
            <NButton secondary @click="reset">
              {{ t('重置') }}
            </NButton>
            <NButton text type="primary" @click="advanced = !advanced">
              {{ advanced ? t('收起筛选') : t('更多筛选') }}<i class="ml-4" :class="advanced ? 'i-fe:chevron-up' : 'i-fe:chevron-down'" />
            </NButton>
            <span class="ml-auto text-12 opacity-50">{{ t('时间按本地时区显示；单次最多查询90天、导出5000条摘要。') }}</span>
          </div>
        </n-form>
        <n-alert v-if="error" type="error" class="mb-12">
          {{ error }}
        </n-alert>
        <n-data-table :columns="columns" :data="rows" :loading="loading" :pagination="false" :row-key="row => row.id" :scroll-x="1330" :single-line="false" :size="appStore.tableSize">
          <template #empty>
            <n-empty :description="t('当前筛选下暂无操作记录')" />
          </template>
        </n-data-table>
        <div class="mt-20 flex items-center justify-between gap-12">
          <span class="text-13 opacity-50">{{ t('共 {0} 条记录', total) }}</span>
          <n-pagination v-model:page="page" v-model:page-size="pageSize" :item-count="total" :page-sizes="[10, 20, 50, 100]" show-size-picker @update:page="loadLogs" @update:page-size="search" />
        </div>
      </n-tab-pane>
      <n-tab-pane v-if="can('AuditRetry')" name="queue" :tab="t('投递异常')" display-directive="if">
        <AuditQueue ref="queue" :operations="operations" @retried="loadStatus" />
      </n-tab-pane>
    </n-tabs>
    <AuditDetails ref="details" @history="history" @operation="operationHistory" />
  </CommonPage>
</template>

<script setup>
import { NButton, NTag } from 'naive-ui'
import { onBeforeUnmount } from 'vue'
import PersonLink from '@/components/common/PersonLink.vue'
import TableActions from '@/components/common/TableActions.vue'
import { t } from '@/i18n'
import { useAppStore, useUserStore } from '@/store'
import api from './api'
import AuditDetails from './AuditDetails.vue'
import AuditQueue from './AuditQueue.vue'
import { actorLabel, auditObjectFilters, objectTypeLabel, operationSummary, queryParams, resultInfo, timeText } from './format'

defineOptions({ name: 'AuditView' })
const appStore = useAppStore()
const route = useRoute()
const user = useUserStore()
const can = code => user.currentRole.code === 'SUPER_ADMIN' || route.meta.btns?.some(button => button.code === code)
function defaults() {
  return {
    range: null,
    actorKeyword: '',
    actorId: '',
    moduleCode: null,
    operationCode: null,
    result: null,
    objectType: null,
    objectId: '',
    objectCode: '',
    parentType: '',
    parentId: '',
    requestId: '',
    operationId: '',
  }
}
const fromLink = auditObjectFilters(route.query)
const filters = reactive({
  ...defaults(),
  ...fromLink,
})
const options = ref({ modules: [], operations: [] })
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const exporting = ref(false)
const error = ref('')
const counts = ref({})
const oldest = ref(null)
const details = ref(null)
const queue = ref(null)
const tab = ref('logs')
const advanced = ref(!!fromLink)
let generation = 0
let statusGeneration = 0
const pending = computed(() => [
  'PREPARED',
  'PENDING',
  'PROCESSING',
  'RETRY',
].reduce((sum, state) => sum + (counts.value[state] || 0), 0))
const modules = computed(() => options.value.modules.map(option => ({
  ...option,
  label: t(option.label),
})))
const operations = computed(() => options.value.operations.map(option => ({
  ...option,
  label: t(option.label),
})))
const operationOptions = computed(() => operations.value.filter(option => !filters.moduleCode || option.module === filters.moduleCode))
const parentGroup = computed({
  get: () => filters.parentId,
  set: (value) => {
    filters.parentId = value
    filters.parentType = value ? 'PARAM_GROUP' : ''
  },
})
const results = computed(() => [
  'SUCCESS',
  'FAILURE',
  'PARTIAL',
  'NO_CHANGE',
  'UNKNOWN',
].map(value => ({ value, label: resultInfo(value).label })))
const objectTypes = computed(() => [
  [
    'DEPARTMENT',
    t('部门'),
  ],
  [
    'NOTIFICATION_GROUP',
    objectTypeLabel('NOTIFICATION_GROUP'),
  ],
  [
    'NOTIFICATION_SCENE',
    objectTypeLabel('NOTIFICATION_SCENE'),
  ],
  [
    'FIELD_DEFINITION',
    t('FIELD_DEFINITION'),
  ],
  [
    'TRADING_CALENDAR',
    t('交易日历'),
  ],
  [
    'DOCUMENT',
    t('文档'),
  ],
  [
    'DOCUMENT_VERSION',
    t('文档版本'),
  ],
  [
    'DOCUMENT_BUSINESS_RECORD',
    t('业务记录'),
  ],
  [
    'INSTITUTION',
    t('机构'),
  ],
  [
    'WORKFLOW_INSTANCE',
    t('流程实例'),
  ],
  [
    'MAIL_ACCOUNT',
    t('邮箱配置'),
  ],
  [
    'MAIL_TEMPLATE',
    t('邮件模板'),
  ],
  [
    'TODO',
    t('待办事项'),
  ],
  [
    'PARAM_GROUP',
    t('参数分类'),
  ],
  [
    'PARAM_ITEM',
    t('参数明细'),
  ],
  [
    'AUDIT_LOG',
    t('审计记录'),
  ],
  [
    'AUDIT_OUTBOX',
    t('投递事件'),
  ],
  [
    'AUDIT_EXPORT',
    t('日志导出'),
  ],
].map(([value, label]) => ({ value, label })))
const columns = computed(() => [
  {
    title: t('操作时间'),
    key: 'occurredAt',
    width: 175,
    render: row => timeText(row.occurredAt),
  },
  {
    title: t('操作者'),
    key: 'actorName',
    width: 210,
    render: row => h(PersonLink, {
      userId: row.actorType === 'USER'
        ? row.actorId
        : null,
      label: actorLabel(row),
    }),
  },
  {
    title: t('操作内容'),
    key: 'summary',
    minWidth: 290,
    render: operationSummary,
  },
  {
    title: t('结果'),
    key: 'result',
    width: 115,
    render: row => h(NTag, { type: resultInfo(row.result).type, size: 'small', bordered: false }, () => resultInfo(row.result).label),
  },
  {
    title: t('有变化对象 / 涉及对象'),
    key: 'objectCount',
    width: 155,
    render: row => `${row.changedObjectCount} / ${row.objectCount}`,
  },
  {
    title: t('当时角色'),
    key: 'actorRoleName',
    width: 145,
    render: row => row.actorRoleName || row.actorRoleCode || '—',
  },
  {
    title: t('来源 IP'),
    key: 'sourceIp',
    width: 145,
    render: row => row.sourceIp || '—',
  },
  {
    title: t('操作'),
    key: 'actions',
    width: 95,
    fixed: 'right',
    render: row => can('AuditDetail')
      ? h(TableActions, {
          actions: [
            {
              key: 'details',
              label: t('详情'),
              secondary: true,
              type: 'primary',
              onClick: () => details.value?.open(row.id),
            },
          ],
        })
      : '—',
  },
])

async function loadStatus() {
  const own = ++statusGeneration
  try {
    const { data } = await api.status()
    if (own === statusGeneration) {
      counts.value = data.counts
      oldest.value = data.oldestPendingAt
    }
  }
  catch { /* The log list remains usable when backlog metrics are temporarily unavailable. */ }
}
async function loadLogs() {
  const own = ++generation
  loading.value = true
  error.value = ''
  loadStatus()
  try {
    const { data } = await api.logs(queryParams(filters, page.value, pageSize.value))
    if (own === generation) {
      rows.value = data.pageData
      total.value = data.total
    }
  }
  catch (failure) {
    if (own === generation) {
      rows.value = []
      total.value = 0
      error.value = failure?.message || t('加载操作日志失败')
    }
  }
  finally {
    if (own === generation)
      loading.value = false
  }
}
async function loadOptions() {
  try {
    const { data } = await api.options()
    options.value = data
  }
  catch { /* Stable operation codes remain visible in the table. */ }
}
function search() {
  page.value = 1
  loadLogs()
}
function reset() {
  Object.assign(filters, defaults())
  search()
}
function refresh() {
  if (tab.value === 'queue') {
    queue.value?.load()
    loadStatus()
  }
  else { loadLogs() }
}
function tabChanged(value) {
  if (value === 'logs')
    loadLogs()
}
function history(object) {
  const range = filters.range
  Object.assign(filters, defaults(), { range, objectType: object.objectType, objectId: object.objectId })
  advanced.value = true
  details.value?.close()
  search()
}
function operationHistory(operationId) {
  const range = filters.range
  Object.assign(filters, defaults(), { range, operationId })
  advanced.value = true
  details.value?.close()
  search()
}
async function exportLogs() {
  exporting.value = true
  error.value = ''
  try {
    const csv = await api.export(queryParams(filters))
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = 'operation-audit.csv'
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  catch (failure) {
    error.value = failure?.message || t('导出失败')
  }
  finally { exporting.value = false }
}
watch(() => filters.moduleCode, () => {
  filters.operationCode = null
})
onMounted(() => {
  loadOptions()
  loadLogs()
})
onBeforeUnmount(() => {
  generation++
  statusGeneration++
})
</script>

<style scoped>
.audit-filters {
  display: grid;
  grid-template-columns: minmax(320px, 1.8fr) repeat(3, minmax(140px, 1fr));
  gap: 0 16px;
}
.audit-more-filters {
  display: grid;
  grid-template-columns: repeat(4, minmax(140px, 1fr));
  gap: 0 16px;
}
@media (max-width: 1150px) {
  .audit-filters,
  .audit-more-filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 650px) {
  .audit-filters,
  .audit-more-filters {
    grid-template-columns: 1fr;
  }
}
</style>
