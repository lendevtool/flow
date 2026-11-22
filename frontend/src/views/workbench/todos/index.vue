<template>
  <CommonPage :title="t('待办事项')">
    <template #action>
      <NButton v-if="can('WorkbenchTodoCreate')" type="primary" @click="editor = {}">
        <i class="i-material-symbols:add mr-4 text-18" aria-hidden="true" />
        {{ t('新建待办') }}
      </NButton>
    </template>

    <n-space justify="space-between" align="center" class="mb-16">
      <n-space>
        <NButton @click="seriesPanel = true">
          {{ t('周期管理') }}
        </NButton>
        <NButton v-if="can('WorkbenchTodoManage')" @click="manageTodo = true">
          {{ t('管理待办') }}
        </NButton>
      </n-space>
      <n-space>
        <NButton :disabled="personalRequest.loading.value || !!personalRequest.error.value" @click="personalSettings = true">
          {{ t('个人维度配置') }}
        </NButton>
        <NButton v-if="can('WorkbenchTodoPublicDimensionManage') && can('ParamMgt') && can('ParamEdit')" @click="publicSettings = true">
          {{ t('公共维度配置') }}
        </NButton>
        <NButton :disabled="!groupOptions.length || !!rangeError" @click="openStatistics">
          {{ t('统计') }}
        </NButton>
      </n-space>
    </n-space>

    <n-tabs v-model:value="scope" type="line" class="mb-12">
      <n-tab v-for="(label, key) in scopeLabels" :key="key" :name="key">
        {{ t(label) }} <NTag size="small" round class="ml-6">
          {{ pageRequest.data.value.scopeCounts[key] }}
        </NTag>
      </n-tab>
    </n-tabs>

    <n-form class="query-form mb-12" label-placement="left" :show-feedback="false" @submit.prevent="keyword = search.trim()">
      <div class="query-grid">
        <n-form-item :label="t('标题')">
          <n-input v-model:value="search" :maxlength="200" clearable :placeholder="t('搜索待办标题')" @clear="keyword = ''" />
        </n-form-item>
        <n-form-item :label="t('来源')">
          <n-select v-model:value="origin" clearable :options="originOptions" :placeholder="t('全部来源')" />
        </n-form-item>
        <n-form-item :label="t('状态')">
          <n-select v-model:value="status" clearable :options="statusOptions" :placeholder="t('全部状态')" />
        </n-form-item>
        <n-form-item :label="t('时间段')">
          <n-select v-model:value="dateField" :options="dateOptions" />
        </n-form-item>
        <DateRangeFilter v-model="dateRange" class="todo-date-filter" />
        <n-space class="query-actions" align="center" justify="end">
          <n-checkbox v-if="route.query.unscheduled === '1'" v-model:checked="includeCompleted">
            {{ t('含已完成') }}
          </n-checkbox>
          <NButton attr-type="submit" :disabled="rangeError">
            {{ t('查询') }}
          </NButton>
        </n-space>
      </div>
    </n-form>
    <n-alert v-if="publicRequest.error.value || personalRequest.error.value" type="error" class="mb-12">
      {{ publicRequest.error.value || personalRequest.error.value }}
      <NButton text @click="reload">
        {{ t('重试') }}
      </NButton>
    </n-alert>
    <WorkbenchDimensions v-model="dimensionFilters" class="mb-16" :public-dimensions="publicDimensions" :personal-dimensions="personalDimensions" />

    <n-space v-if="columnOptions.length" justify="end" class="mb-8">
      <n-popover trigger="click" placement="bottom-end">
        <template #trigger>
          <NButton size="small">
            {{ t('列设置') }}
          </NButton>
        </template>
        <n-checkbox-group v-model:value="visibleColumns">
          <n-space vertical>
            <n-checkbox v-for="column in columnOptions" :key="column.value" :value="column.value">
              {{ column.label }}
            </n-checkbox>
          </n-space>
        </n-checkbox-group>
      </n-popover>
    </n-space>
    <n-alert v-if="pageRequest.error.value" type="error" class="mb-12">
      {{ pageRequest.error.value }}
      <NButton text @click="loadPage">
        {{ t('重试') }}
      </NButton>
    </n-alert>
    <n-data-table
      :columns="columns"
      :data="tableRows"
      :loading="pageRequest.loading.value"
      :row-key="row => row.id"
      :scroll-x="scrollWidth"
      size="small"
      striped
    >
      <template #empty>
        <n-empty :description="t('没有匹配的待办')" />
      </template>
    </n-data-table>
    <n-space justify="end" class="mt-12">
      <n-pagination v-model:page="page" v-model:page-size="size" :item-count="pageRequest.data.value.total" :page-sizes="[20, 50, 100]" show-size-picker />
    </n-space>

    <TodoDrawer v-if="selected !== null" :id="selected" :key="selected" ref="drawer" :public-dimensions="publicDimensions" :personal-dimensions="personalDimensions" @close="closeDetail" @edit="todo => editor = { todo }" @changed="loadPage" />
    <TodoForm v-if="editor" :todo="editor.todo" :public-dimensions="publicDimensions" :personal-dimensions="personalDimensions" @close="editor = null" @saved="saved" @conflict="drawer?.refresh()" />
    <PersonalDimensionSettings v-if="personalSettings" :dimensions="personalDimensions" @close="personalSettings = false" @saved="dimensionsSaved" />
    <PublicDimensionSettings v-if="publicSettings" :can-create="can('ParamCreate')" @close="publicSettings = false" @changed="dimensionsSaved" />
    <SeriesManager v-if="seriesPanel" :public-dimensions="publicDimensions" @close="seriesPanel = false" @saved="loadPage" />
    <TodoManage v-if="manageTodo" @close="manageTodo = false" @saved="loadPage" />

    <n-modal v-model:show="showStatistics" preset="card" :title="t('待办统计')" class="max-w-95vw w-650">
      <n-form-item :label="t('按维度分组')">
        <n-select v-model:value="groupBy" :options="groupOptions" />
      </n-form-item>
      <n-alert v-if="stats.error.value" type="error" class="mb-12">
        {{ stats.error.value }}
        <NButton text @click="loadStats">
          {{ t('重试') }}
        </NButton>
      </n-alert>
      <n-data-table :columns="statColumns" :data="stats.data.value" :loading="stats.loading.value" />
    </n-modal>
  </CommonPage>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { VNodeChild } from 'vue'
import type { Origin, Scope, Statistics, Todo, TodoQuery } from '../api'
import type { DimensionFilter } from '../model'
import type { DateRange } from '@/utils/businessDate'
import { NButton, NEllipsis, NTag } from 'naive-ui'
import { computed, h, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DateRangeFilter from '@/components/common/DateRangeFilter.vue'
import { t } from '@/i18n'
import { isDateRangeReversed } from '@/utils/businessDate'
import api from '../api'
import { dimensionQuery, formatBusinessToday, originLabels, priorityLabels, scopeLabels, statusLabels } from '../model'
import PersonalDimensionSettings from '../PersonalDimensionSettings.vue'
import PublicDimensionSettings from '../PublicDimensionSettings.vue'
import SeriesManager from '../SeriesManager.vue'
import TodoDrawer from '../TodoDrawer.vue'
import TodoForm from '../TodoForm.vue'
import TodoManage from '../TodoManage.vue'
import { useDimensionMetadata, useWorkbenchPermission, useWorkbenchRequest } from '../useWorkbench'
import WorkbenchDimensions from '../WorkbenchDimensions.vue'

defineOptions({ name: 'WorkbenchTodo' })

const route = useRoute()
const router = useRouter()
const can = useWorkbenchPermission()
const { publicRequest, personalRequest, reload } = useDimensionMetadata()
const publicDimensions = publicRequest.data
const personalDimensions = personalRequest.data
const scope = ref<Scope>('mine')
const search = ref('')
const keyword = ref('')
const origin = ref<Origin | null>(null)
const status = ref<TodoQuery['status'] | null>(null)
const dateField = ref<'due' | 'plan'>('due')
const dateRange = ref<DateRange>(null)
const includeCompleted = ref(false)
const dimensionFilters = ref<DimensionFilter>({})
const page = ref(1)
const size = ref(20)
const visibleColumns = ref<string[]>([])
const selected = ref<number | null>(null)
const drawer = ref<InstanceType<typeof TodoDrawer> | null>(null)
const editor = ref<{ todo?: Todo } | null>(null)
const personalSettings = ref(false)
const publicSettings = ref(false)
const seriesPanel = ref(false)
const manageTodo = ref(false)
const showStatistics = ref(false)
const groupBy = ref('')
const stats = useWorkbenchRequest<Statistics[]>([])
const pageRequest = useWorkbenchRequest({
  records: [] as Todo[],
  total: 0,
  scopeCounts: { mine: 0, created: 0, done: 0, all: 0 },
})

const originOptions = computed(() => Object.entries(originLabels).map(([value, label]) => ({
  value,
  label: t(label),
})))
const statusOptions = computed(() => Object.entries({
  ...statusLabels,
  OVERDUE: '逾期',
  TODAY: '今日到期',
  UNSCHEDULED: '未排期',
}).map(([value, label]) => ({
  value,
  label: t(label),
})))
const dateOptions = computed(() => [
  {
    label: t('截止日期'),
    value: 'due',
  },
  {
    label: t('计划日期'),
    value: 'plan',
  },
])
const rangeError = computed(() => isDateRangeReversed(dateRange.value))

const dimensionColumns = computed(() => [
  ...publicDimensions.value.map(field => ({
    label: `${t('公共维度')} · ${field.name}`,
    value: `public:${field.slot}`,
    shown: field.enabled || field.options.length > 0,
    render: (todo: Todo) => field.options.find(option => option.id === todo.dimensions[field.slot])?.name ?? '—',
  })),
  ...personalDimensions.value.map(field => ({
    label: `${t('我的个人维度')} · ${field.name}`,
    value: `personal:${field.slot}`,
    shown: field.enabled || field.options.length > 0,
    render: (todo: Todo) => {
      const value = todo.personalDimensions[field.slot]
      return field.options.find(option => option.key === value)?.name ?? value ?? '—'
    },
  })),
].filter(field => field.shown && field.label.trim().length))
const columnOptions = computed(() => dimensionColumns.value.map(({ label, value }) => ({ label, value })))
const groupOptions = columnOptions
const statColumns = computed<DataTableColumns<Statistics>>(() => [
  {
    title: t('维度值'),
    key: 'name',
    render: row => row.name || t('未填写'),
  },
  {
    title: t('总数'),
    key: 'total',
  },
  {
    title: t('未完成'),
    key: 'open',
  },
  {
    title: t('已完成'),
    key: 'done',
  },
  {
    title: t('逾期'),
    key: 'overdue',
  },
])

type TableRow = Todo | { id: string, group: string }
const tableRows = computed<TableRow[]>(() => {
  const today = formatBusinessToday()
  const rows: TableRow[] = []
  let previous = ''
  for (const todo of pageRequest.data.value.records) {
    const group = groupLabel(todo, today)
    if (group !== previous)
      rows.push({ id: `${group}-${todo.id}`, group })
    previous = group
    rows.push(todo)
  }
  return rows
})
const visibleDimensionColumns = computed(() => dimensionColumns.value.filter(field => visibleColumns.value.includes(field.value)))
const columns = computed<DataTableColumns<TableRow>>(() => {
  const column = (title: string, key: string, width: number, render: (row: Todo) => VNodeChild): DataTableColumns<TableRow>[number] => ({
    title: t(title),
    key,
    width,
    ellipsis: { tooltip: true },
    colSpan: row => 'group' in row
      ? key === 'title'
        ? 7 + visibleDimensionColumns.value.length
        : 0
      : 1,
    render: row => 'group' in row
      ? key === 'title'
        ? h(NTag, { size: 'small', type: 'primary', strong: true }, () => t(row.group))
        : null
      : render(row),
  })
  return [
    column('待办事项', 'title', 250, row => h(NButton, {
      text: true,
      type: 'primary',
      onClick: () => { selected.value = row.id },
    }, () => h(NEllipsis, {
      style: { maxWidth: '225px' },
    }, () => row.title))),
    column('来源', 'origin', 104, row => t(originLabels[row.origin])),
    column('创建人', 'owner', 116, row => row.ownerName || row.sourceLabel || row.sourceModule || t('系统')),
    column('计划日期', 'plan', 192, row => row.startDate
      ? `${row.startDate} ~ ${row.endDate}`
      : '—'),
    column('截止日期', 'due', 114, row => row.dueDate ?? '—'),
    ...visibleDimensionColumns.value.map(field => column(field.label, field.value, 166, field.render)),
    column('优先级', 'priority', 90, row => h(NTag, {
      size: 'small',
      type: row.priority === 'HIGH'
        ? 'warning'
        : 'default',
    }, () => t(priorityLabels[row.priority]))),
    column('状态', 'status', 95, row => h(NTag, {
      size: 'small',
      type: row.status === 'DONE'
        ? 'success'
        : 'default',
    }, () => t(statusLabels[row.status]))),
  ]
})
const scrollWidth = computed(() => 961 + 166 * visibleDimensionColumns.value.length)

const filters = computed<TodoQuery>(() => ({
  scope: scope.value,
  keyword: keyword.value,
  origin: origin.value ?? undefined,
  status: status.value ?? undefined,
  dateField: dateField.value,
  fromDate: dateRange.value?.[0] ?? undefined,
  toDate: dateRange.value?.[1] ?? undefined,
  includeCompleted: route.query.unscheduled === '1' && status.value === 'UNSCHEDULED'
    ? includeCompleted.value
    : undefined,
  ...dimensionFilters.value,
}))

function groupLabel(todo: Todo, today: string) {
  if (todo.status === 'DONE' || todo.status === 'CANCELLED')
    return '已关闭'
  if (!todo.dueDate)
    return '无截止日期'
  if (todo.dueDate < today)
    return '逾期'
  if (todo.dueDate === today)
    return '今日到期'
  return '未来到期'
}

const linkedTodoId = computed(() => {
  const todo = route.query.todo
  if (route.name !== 'WorkbenchTodo' || typeof todo !== 'string' || !/^[1-9]\d*$/.test(todo))
    return null
  const id = Number(todo)
  return Number.isSafeInteger(id)
    ? id
    : null
})

watch(linkedTodoId, (id, previousId) => {
  if (id !== null || selected.value === previousId)
    selected.value = id
}, { immediate: true })

function closeDetail() {
  selected.value = null
  if (linkedTodoId.value !== null) {
    void router.replace({
      query: { ...route.query, todo: undefined },
    })
  }
}

watch(() => route.query, (query) => {
  if (route.name !== 'WorkbenchTodo')
    return
  scope.value = query.scope === 'created'
    ? 'created'
    : 'mine'
  origin.value = typeof query.origin === 'string' && Object.hasOwn(originLabels, query.origin)
    ? query.origin as Origin
    : null
  status.value = query.unscheduled === '1'
    ? 'UNSCHEDULED'
    : null
  includeCompleted.value = query.includeCompleted === 'true'
  dimensionFilters.value = dimensionQuery(query)
}, { immediate: true })

watch([filters, size], () => {
  page.value = 1
})
watch([filters, page, size], loadPage, { immediate: true })
watch([showStatistics, groupBy, filters], () => {
  if (showStatistics.value)
    loadStats()
})

async function loadPage() {
  if (rangeError.value) {
    pageRequest.reset()
    return
  }
  const result = await pageRequest.run(() => api.list({ ...filters.value, page: page.value, size: size.value }))
  if (result && page.value > 1 && !result.records.length)
    page.value = Math.max(1, Math.ceil(result.total / size.value))
}

function loadStats() {
  if (groupBy.value && !rangeError.value)
    return stats.run(() => api.statistics(filters.value, groupBy.value))
}

function openStatistics() {
  if (!groupOptions.value.some(option => option.value === groupBy.value))
    groupBy.value = groupOptions.value[0]?.value ?? ''
  stats.reset()
  showStatistics.value = true
}

function saved() {
  editor.value = null
  drawer.value?.refresh()
  loadPage()
}

function dimensionsSaved() {
  reload()
  loadPage()
}
</script>

<style scoped>
.query-form {
  container-type: inline-size;
}

.query-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
  gap: 12px;
}

.query-actions {
  grid-column: -2 / -1;
}

@container (min-width: 492px) {
  .query-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .todo-date-filter {
    grid-column: span 2;
  }
}

@container (min-width: 744px) {
  .query-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@container (min-width: 996px) {
  .query-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}
</style>
