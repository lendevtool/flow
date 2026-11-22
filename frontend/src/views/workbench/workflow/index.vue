<template>
  <CommonPage>
    <template #action>
      <NButton v-if="!standalone && permission.hasRoute('/workbench/workflow-catalog')" type="primary" @click="router.push('/workbench/workflow-catalog')">
        {{ t('发起流程') }}
      </NButton>
    </template>

    <WorkflowPage
      v-if="selection"
      :key="selection.id"
      :mode="selection.mode"
      :detail="detail"
      :loading="detailLoading"
      :focus-todo="selection.focusTodo"
      @changed="refreshDetail"
      @close="close"
      @retry="loadDetail(selection.id)"
    />
    <div v-if="!standalone" class="h-full flex flex-col">
      <n-tabs type="line" :value="scope" class="mb-12 shrink-0" @update:value="changeScope">
        <n-tab v-for="filter in filters" :key="filter.id" :name="filter.id" :disabled="rangeError">
          {{ t(filter.label) }}
          <NTag v-if="scopeCounts" round size="small" class="ml-6">
            {{ scopeCounts[filter.id] }}
          </NTag>
        </n-tab>
      </n-tabs>
      <n-alert v-if="listFailed" type="error" class="mb-12 shrink-0">
        {{ t('加载失败') }}
        <NButton text @click="table?.handleSearch(true)">
          {{ t('重试') }}
        </NButton>
      </n-alert>
      <MeCrud
        ref="table"
        v-model:query-items="queryItems"
        :columns="columns"
        :get-data="getList"
        :query-disabled="rangeError"
        :page-size="20"
        :scroll-x="1120"
        class="min-h-0 flex-1"
        @update:query-failed="listFailed = $event"
      >
        <MeQueryItem :label="t('流程标题')" :label-width="95" :content-width="180">
          <n-input v-model:value="queryItems.title" :placeholder="t('搜索流程标题')" :maxlength="200" clearable />
        </MeQueryItem>
        <MeQueryItem :label="t('发起人')" :label-width="72" :content-width="150">
          <n-input v-model:value="queryItems.ownerName" :placeholder="t('搜索发起人')" :maxlength="100" clearable />
        </MeQueryItem>
        <DateRangeFilter v-model="queryItems.startedRange" :label="t('发起日期')" style="width: min(520px, 68vw)" />
        <template #empty="{ loading }">
          <n-empty v-if="!loading && !listFailed" :description="t('暂无流程')" />
        </template>
      </MeCrud>
    </div>
  </CommonPage>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { Detail, Instance, Scope } from './api'
import type { DateRange } from '@/utils/businessDate'
import { NButton, NTag } from 'naive-ui'
import { computed, h, onActivated, onDeactivated, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DateRangeFilter from '@/components/common/DateRangeFilter.vue'
import MeCrud from '@/components/me/crud/index.vue'
import MeQueryItem from '@/components/me/crud/QueryItem.vue'
import { t } from '@/i18n'
import { usePermissionStore } from '@/store'
import { isDateRangeReversed } from '@/utils/businessDate'
import api from './api'
import WorkflowPage from './WorkflowPage.vue'

defineOptions({ name: 'WorkflowMine' })

const route = useRoute()
const router = useRouter()
const permission = usePermissionStore()
const scope = ref<Scope>('MINE')
const filters: { id: Scope, label: string }[] = [
  { id: 'MINE', label: '我的待办' },
  { id: 'STARTED', label: '我发起的' },
  { id: 'APPROVED', label: '我审批的' },
  { id: 'DONE', label: '已完成' },
  { id: 'ALL', label: '全部' },
]
const status = { RUNNING: '流程进行中', DONE: '已完成', CANCELLED: '已取消' }
const standalone = computed(() => route.name === 'WorkflowTask')
const selection = ref<{ id: number, mode: 'task' | 'detail', focusTodo?: number }>()
interface QueryItems {
  title: string
  ownerName: string
  startedRange: DateRange
}
const table = ref<InstanceType<typeof MeCrud> | null>(null)
const queryItems = ref<QueryItems>({ title: '', ownerName: '', startedRange: null })
const rangeError = computed(() => isDateRangeReversed(queryItems.value.startedRange))
const scopeCounts = ref<Record<Scope, number>>()
const listFailed = ref(false)
const detail = ref<Detail>()
const detailLoading = ref(false)
let listRequestId = 0
let detailRequestId = 0

const columns = computed<DataTableColumns<Instance>>(() => [
  {
    title: t('流程标题'),
    key: 'title',
    minWidth: 230,
    ellipsis: { tooltip: true },
  },
  {
    title: t('发起人'),
    key: 'ownerName',
    width: 110,
    ellipsis: { tooltip: true },
    render: row => row.ownerName || '—',
  },
  {
    title: t('新建时间'),
    key: 'startedAt',
    width: 155,
    render: row => row.startedAt?.replace('T', ' ').slice(0, 16) || '—',
  },
  {
    title: t('当前节点'),
    key: 'currentNodes',
    width: 155,
    ellipsis: { tooltip: true },
    render: row => row.currentNodes?.map(node => node.title).join('、') || '—',
  },
  {
    title: t('当前处理人'),
    key: 'assignees',
    width: 205,
    ellipsis: { tooltip: true },
    render: (row) => {
      const nodes = row.currentNodes ?? []
      return nodes.map((node) => {
        const handler = node.status === 'WAITING_ASSIGNMENT'
          ? [
              t('待分派'),
              node.roleName,
            ].filter(Boolean).join(' · ')
          : node.assigneeNames.join('、') || node.roleName || '—'
        return nodes.length > 1
          ? `${node.title} · ${handler}`
          : handler
      }).join(' / ') || '—'
    },
  },
  {
    title: t('状态'),
    key: 'status',
    width: 115,
    render: row => h(NTag, {
      size: 'small',
      type: row.status === 'DONE'
        ? 'success'
        : 'default',
    }, () => t(status[row.status])),
  },
  {
    title: t('操作'),
    key: 'actions',
    width: 150,
    render: row => h('div', { class: 'flex gap-12' }, [
      scope.value === 'MINE'
        ? h(NButton, {
            text: true,
            type: 'primary',
            onClick: () => open(row.id, true),
          }, () => t('处理'))
        : null,
      h(NButton, {
        text: true,
        type: 'primary',
        onClick: () => open(row.id, false),
      }, () => t('查看详情')),
    ]),
  },
])

async function getList({ title, ownerName, startedRange, pageNo, pageSize }: QueryItems & { pageNo: number, pageSize: number }) {
  const current = ++listRequestId
  try {
    const result = await api.list({
      scope: scope.value,
      pageNo,
      pageSize,
      title: title.trim() || undefined,
      ownerName: ownerName.trim() || undefined,
      startedFrom: startedRange?.[0] ?? undefined,
      startedTo: startedRange?.[1] ?? undefined,
    })
    if (current === listRequestId)
      scopeCounts.value = result.data.scopeCounts
    return result
  }
  catch (error) {
    if (current === listRequestId)
      scopeCounts.value = undefined
    throw error
  }
}

async function loadDetail(id: number) {
  const current = ++detailRequestId
  detailLoading.value = true
  detail.value = undefined
  try {
    const { data } = await api.detail(id)
    if (current === detailRequestId)
      detail.value = data
  }
  catch {
    // 请求错误由统一拦截器显示。
  }
  finally {
    if (current === detailRequestId)
      detailLoading.value = false
  }
}

function changeScope(id: Scope) {
  scope.value = id
  void table.value?.handleSearch()
}
function refreshDetail(result: Detail) {
  detail.value = result
  void table.value?.handleSearch(true)
}
function open(id: number, processing: boolean, focusTodo?: number) {
  // 列表内打开抽屉无需切换路由，保留当前页面和筛选状态。
  selection.value = {
    id,
    mode: processing ? 'task' : 'detail',
    focusTodo,
  }
  void loadDetail(id)
}
function restoreLinkedSelection() {
  const id = Number(route.params.id ?? route.query.id)
  if (id > 0 && !selection.value)
    open(id, route.query.mode === 'task', Number(route.query.todo) || undefined)
}
function clearSelection() {
  detailRequestId++
  selection.value = undefined
  detail.value = undefined
  detailLoading.value = false
}
function close() {
  clearSelection()
  if (!standalone.value) {
    if (route.query.id)
      void router.replace({ path: route.path, query: {} })
    return
  }
  const listPath = '/workbench/workflows'
  void router.push(permission.hasRoute(listPath)
    ? listPath
    : '/')
}
onMounted(() => {
  restoreLinkedSelection()
  void table.value?.handleSearch()
})
onActivated(restoreLinkedSelection)
onDeactivated(clearSelection)
</script>
