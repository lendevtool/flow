<template>
  <CommonPage>
    <template #title-suffix>
      <span class="hidden text-13 opacity-50 md:inline" />
    </template>
    <section class="param-workspace">
      <n-split :direction="compact ? 'vertical' : 'horizontal'" :default-size="compact ? 0.4 : 0.29" :min="0.22" :max="0.55" :resize-trigger-size="4">
        <template #1>
          <section class="param-panel group-panel">
            <div class="panel-heading">
              <span>{{ t('参数分类') }}</span><span class="text-12 font-normal opacity-50">{{ t('{0} 个分类', groupTotal) }}</span>
            </div>
            <div class="toolbar">
              <n-button v-if="can('ParamCreate')" type="primary" @click="groupEditor.open()">
                <i class="i-fe:plus mr-6" />{{ t('新建') }}
              </n-button>
              <n-button v-if="can('ParamSort')" secondary type="info" :disabled="groupLoading || !groupTotal || ordering" :loading="groupOrdering" @click="openGroupSort">
                <i class="i-fe:shuffle mr-6" />{{ t('调整排序') }}
              </n-button>
              <n-button v-if="can('ParamRefresh')" secondary :loading="refreshing" @click="refreshCache">
                <i class="i-fe:refresh-cw mr-6" />{{ t('刷新缓存') }}
              </n-button>
            </div>
            <n-input v-model:value="groupKeyword" :placeholder="t('搜索参数编码 / 名称')" clearable class="mb-16" :maxlength="100" @keyup.enter="loadGroups(true)" @clear="loadGroups(true)">
              <template #prefix>
                <i class="i-fe:search opacity-50" />
              </template>
              <template #suffix>
                <n-button text :aria-label="t('搜索分类')" @click="loadGroups(true)">
                  {{ t('查询') }}
                </n-button>
              </template>
            </n-input>
            <n-alert v-if="groupError" type="error" class="mb-12" :show-icon="false">
              {{ groupError }} <n-button text type="primary" @click="loadGroups()">
                {{ t('重试') }}
              </n-button>
            </n-alert>
            <div class="table-area">
              <n-data-table
                :columns="groupColumns" :data="groupRows" :loading="groupLoading" :row-key="rowKey"
                :row-props="groupRowProps" :row-class-name="groupRowClass" :size="appStore.tableSize" :single-line="false" :scroll-x="420"
              >
                <template #empty>
                  <n-empty :description="groupKeyword ? t('没有匹配的参数分类') : t('暂无参数分类，点击新建开始维护')" />
                </template>
              </n-data-table>
            </div>
            <div class="table-footer">
              <n-pagination v-model:page="groupPage" :page-size="10" :item-count="groupTotal" :page-slot="3" size="small" @update:page="loadGroups()">
                <template #suffix>
                  {{ t('共 {0} 条数据', groupTotal) }}
                </template>
              </n-pagination>
            </div>
          </section>
        </template>
        <template #2>
          <section class="param-panel item-panel">
            <div class="panel-heading">
              <div class="min-w-0 flex items-center gap-10">
                <span class="truncate">{{ displayName(selected?.name, selected?.nameEn) || t('参数明细') }}</span><NTag v-if="selected" size="small" :bordered="false">
                  {{ selected.code }}
                </NTag>
              </div>
              <n-tooltip>
                <template #trigger>
                  <i class="i-fe:info shrink-0 opacity-40" />
                </template>{{ t('编码、名称可直接作为字典选项；固定值列与 JSON 扩展字段用于维护配置。') }}
              </n-tooltip>
            </div>
            <div class="toolbar">
              <n-button v-if="can('ParamCreate')" type="primary" :disabled="!selected || groupLoading" @click="itemEditor.open(selected)">
                <i class="i-fe:plus mr-6" />{{ t('新建') }}
              </n-button>
              <n-button v-if="can('ParamSort')" secondary type="info" :disabled="!selected || !itemTotal || groupLoading || groupOrdering" :loading="ordering" @click="openSort">
                <i class="i-fe:shuffle mr-6" />{{ t('调整排序') }}
              </n-button>
              <div class="flex-1" />
              <n-popover trigger="click" placement="bottom-end">
                <template #trigger>
                  <n-button secondary :disabled="!selected" :aria-label="t('显示列设置')">
                    <i class="i-fe:columns mr-6" />{{ t('显示列') }}
                  </n-button>
                </template>
                <div class="max-h-360 overflow-y-auto p-4">
                  <n-checkbox-group v-model:value="visibleColumns">
                    <n-space vertical>
                      <n-checkbox v-for="field in allFields" :key="`${field.storage}:${field.key}`" :value="`${field.storage}:${field.key}`" :label="field.label" />
                      <n-checkbox value="createdByName" :label="t('创建人')" /><n-checkbox value="createdAt" :label="t('创建时间')" />
                      <n-checkbox value="updatedByName" :label="t('更新人')" /><n-checkbox value="updatedAt" :label="t('更新时间')" />
                    </n-space>
                  </n-checkbox-group>
                </div>
              </n-popover>
            </div>
            <n-input v-model:value="itemKeyword" :placeholder="t('搜索明细编码 / 名称')" clearable :disabled="!selected" class="mb-16" :maxlength="100" @keyup.enter="loadItems(true)" @clear="loadItems(true)">
              <template #prefix>
                <i class="i-fe:search opacity-50" />
              </template>
              <template #suffix>
                <n-button text :disabled="!selected" :aria-label="t('搜索明细')" @click="loadItems(true)">
                  {{ t('查询') }}
                </n-button>
              </template>
            </n-input>
            <n-alert v-if="itemError" type="error" class="mb-12" :show-icon="false">
              {{ itemError }} <n-button text type="primary" @click="loadItems()">
                {{ t('重试') }}
              </n-button>
            </n-alert>
            <div class="table-area">
              <n-data-table :columns="itemColumns" :data="itemRows" :loading="itemLoading" :row-key="rowKey" :size="appStore.tableSize" :single-line="false" :scroll-x="itemScrollX">
                <template #empty>
                  <n-empty :description="!selected ? t('选择左侧参数分类，查看对应明细') : itemKeyword ? t('没有匹配的参数明细') : t('该分类暂无明细')" />
                </template>
              </n-data-table>
            </div>
            <div class="table-footer">
              <n-pagination v-model:page="itemPage" :page-size="10" :item-count="itemTotal" :page-slot="compact ? 3 : 5" size="small" @update:page="loadItems()">
                <template #suffix>
                  {{ t('共 {0} 条数据', itemTotal) }}
                </template>
              </n-pagination>
            </div>
          </section>
        </template>
      </n-split>
    </section>
    <GroupEditor ref="groupEditor" @saved="groupSaved" />
    <ItemEditor ref="itemEditor" @saved="itemsSaved" />
    <ParamDetails ref="details" />
    <SortEditor ref="sortEditor" @saved="itemsSaved" @groups-saved="groupsSorted" />
  </CommonPage>
</template>

<script setup>
import { useWindowSize } from '@vueuse/core'
import dayjs from 'dayjs'
import { NTag } from 'naive-ui'
import { onBeforeUnmount } from 'vue'
import TableActions from '@/components/common/TableActions.vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import { useAppStore, useUserStore } from '@/store'
import api from './api'
import GroupEditor from './components/GroupEditor.vue'
import ItemEditor from './components/ItemEditor.vue'
import ParamDetails from './components/ParamDetails.vue'
import SortEditor from './components/SortEditor.vue'
import { displayValue, fieldDefinitions, latestRequest } from './fields'

defineOptions({ name: 'ParamMgt' })
const appStore = useAppStore()
const route = useRoute()
const user = useUserStore()
const can = code => user.currentRole.code === 'SUPER_ADMIN' || route.meta.btns?.some(button => button.code === code)
const { width } = useWindowSize()
const compact = computed(() => width.value < 1000)
const rowKey = row => row.id
const groupEditor = ref(null)
const itemEditor = ref(null)
const details = ref(null)
const sortEditor = ref(null)
const groupRows = ref([])
const groupKeyword = ref('')
const workbenchDimensionGroup = 'WORKBENCH_TODO_DIMENSION'
const groupPage = ref(1)
const groupTotal = ref(0)
const groupLoading = ref(false)
const groupError = ref('')
const itemRows = ref([])
const itemKeyword = ref('')
const itemPage = ref(1)
const itemTotal = ref(0)
const itemLoading = ref(false)
const itemError = ref('')
const selected = ref(null)
const refreshing = ref(false)
const ordering = ref(false)
const groupOrdering = ref(false)
const visibleColumns = ref([])
const groupRequests = latestRequest()
const itemRequests = latestRequest()
const allFields = computed(() => fieldDefinitions(selected.value?.fieldConfig))

const groupColumns = computed(() => [
  {
    title: t('参数'),
    key: 'code',
    width: 140,
    ellipsis: { tooltip: true },
  },
  {
    title: t('参数名'),
    key: 'name',
    width: 140,
    ellipsis: { tooltip: true },
    render: row => h('span', {
      style: row.enable ? '' : 'opacity: .45',
    }, displayName(row.name, row.nameEn)),
  },
  {
    title: t('操作'),
    key: 'actions',
    width: 120,
    fixed: 'right',
    render: row => actions(row, true),
  },
])
const itemColumns = computed(() => {
  const columns = [
    {
      title: t('操作'),
      key: 'actions',
      width: 120,
      fixed: 'left',
      render: row => actions(row, false),
    },
    {
      title: t('序号'),
      key: 'sort',
      width: 70,
    },
    {
      title: t('编码'),
      key: 'code',
      width: 160,
      ellipsis: { tooltip: true },
    },
    {
      title: t('名称'),
      key: 'name',
      width: 170,
      ellipsis: { tooltip: true },
      render: row => displayName(row.name, row.nameEn),
    },
  ]
  for (const field of allFields.value.filter(field => visibleColumns.value.includes(`${field.storage}:${field.key}`))) {
    columns.push({
      title: field.label,
      key: `${field.storage}:${field.key}`,
      width: 160,
      ellipsis: { tooltip: true },
      render: row => displayValue(field, field.storage === 'fixed'
        ? row[field.key]
        : row.extra?.[field.key]),
    })
  }
  columns.push({
    title: t('状态'),
    key: 'enable',
    width: 80,
    render: row => h(NTag, {
      size: 'small',
      bordered: false,
      type: row.enable ? 'success' : 'default',
    }, () => row.enable
      ? t('启用')
      : t('停用')),
  })
  for (const [key, title, width] of [
    [
      'createdByName',
      t('创建人'),
      110,
    ],
    [
      'createdAt',
      t('创建时间'),
      170,
    ],
    [
      'updatedByName',
      t('更新人'),
      110,
    ],
    [
      'updatedAt',
      t('更新时间'),
      170,
    ],
  ]) {
    if (visibleColumns.value.includes(key)) {
      columns.push({
        title,
        key,
        width,
        ellipsis: { tooltip: true },
        render: row => key.endsWith('At')
          ? dayjs(row[key]).format('YYYY-MM-DD HH:mm:ss')
          : row[key] || '—',
      })
    }
  }
  return columns
})
const itemScrollX = computed(() => itemColumns.value.reduce((sum, column) => sum + column.width, 0))

function actions(row, group) {
  const rowActions = []
  if (can('ParamEdit')) {
    rowActions.push({
      key: 'edit',
      label: t('编辑'),
      icon: 'i-fe:edit text-16',
      secondary: true,
      onClick: () => openRecord(row, group, false),
    })
  }
  if (can('ParamDelete')) {
    rowActions.push({
      key: 'delete',
      label: t('删除'),
      icon: 'i-fe:trash-2 text-16',
      secondary: true,
      onClick: () => removeRecord(row, group),
    })
  }
  rowActions.push({
    key: 'details',
    label: t('查看详情'),
    icon: 'i-fe:file-text text-16',
    secondary: true,
    onClick: () => openRecord(row, group, true),
  })
  return h(TableActions, {
    actions: rowActions,
    iconOnly: true,
  })
}
function groupRowProps(row) {
  return {
    style: 'cursor: pointer',
    onClick: () => selectGroup(row),
    tabindex: 0,
    onKeydown: (event) => {
      if (event.key === 'Enter')
        selectGroup(row)
    },
  }
}
function groupRowClass(row) {
  return row.id === selected.value?.id
    ? 'selected-param'
    : ''
}

function selectGroup(group, force = false) {
  const changed = selected.value?.id !== group?.id
  selected.value = group
  if (!changed && !force)
    return
  itemRequests.invalidate()
  itemRows.value = []
  itemTotal.value = 0
  itemKeyword.value = ''
  itemPage.value = 1
  itemError.value = ''
  itemLoading.value = false
  visibleColumns.value = [
    ...fieldDefinitions(group?.fieldConfig).filter(field => field.showInTable).map(field => `${field.storage}:${field.key}`),
    'updatedByName',
    'updatedAt',
  ]
  if (group)
    loadItems()
}

async function loadGroups(reset = false, preferredId = null) {
  if (reset)
    groupPage.value = 1
  groupLoading.value = true
  groupError.value = ''
  const result = await groupRequests.run(() => api.groups({ pageNo: groupPage.value, pageSize: 10, keyword: groupKeyword.value }))
  if (!result.current)
    return
  groupLoading.value = false
  if (result.error) {
    groupError.value = result.error.message || t('分类加载失败，请重试')
    return
  }
  groupRows.value = result.data.data.pageData
  groupTotal.value = result.data.data.total
  if (!groupRows.value.length && groupPage.value > 1) {
    groupPage.value--
    return loadGroups()
  }
  const target = groupRows.value.find(row => row.id === (preferredId || selected.value?.id)) || groupRows.value[0] || null
  selectGroup(target, !!preferredId)
}
async function loadItems(reset = false) {
  if (!selected.value)
    return
  if (reset)
    itemPage.value = 1
  const groupId = selected.value.id
  itemLoading.value = true
  itemError.value = ''
  const result = await itemRequests.run(() => api.items(groupId, { pageNo: itemPage.value, pageSize: 10, keyword: itemKeyword.value }))
  if (!result.current)
    return
  itemLoading.value = false
  if (result.error) {
    itemError.value = result.error.message || t('明细加载失败，请重试')
    return
  }
  itemRows.value = result.data.data.pageData
  itemTotal.value = result.data.data.total
  if (!itemRows.value.length && itemPage.value > 1) {
    itemPage.value--
    return loadItems()
  }
}
async function openRecord(row, isGroup, readonly) {
  const category = selected.value
  if (!isGroup && !category)
    return
  try {
    const { data } = isGroup
      ? await api.group(row.id)
      : await api.item(category.id, row.id)
    if (readonly)
      details.value.open(data, isGroup ? null : category)
    else if (isGroup)
      groupEditor.value.open(data)
    else
      itemEditor.value.open(category, data)
  }
  catch { /* Request client displays read errors. */ }
}
function removeRecord(row, isGroup) {
  const category = selected.value
  const dialog = $dialog.warning({
    title: isGroup
      ? t('删除参数分类')
      : t('删除参数明细'),
    content: t('确定删除「{0}」吗？{1}', displayName(row.name, row.nameEn), isGroup
      ? t(' 分类下存在明细时不允许删除。')
      : ''),
    positiveText: t('删除'),
    negativeText: t('取消'),
    async onPositiveClick() {
      dialog.loading = true
      try {
        if (isGroup)
          await api.deleteGroup(row.id)
        else
          await api.deleteItem(category.id, row.id)
        $message.success(t('删除成功'))
        if (isGroup)
          await loadGroups()
        else
          await itemsSaved(category.id)
      }
      catch { return false }
      finally { dialog.loading = false }
    },
  })
}
async function groupSaved(group, created) {
  if (created) {
    groupKeyword.value = group.code
    groupPage.value = 1
  }
  await loadGroups(false, group.id)
}
async function itemsSaved(groupId) {
  if (selected.value?.id === groupId)
    await loadItems()
  await loadGroups()
}
async function openSort() {
  const category = selected.value
  ordering.value = true
  try {
    const { data } = await api.order(category.id)
    sortEditor.value.open(category, data)
  }
  catch { /* Request client displays read errors. */ }
  finally { ordering.value = false }
}
async function openGroupSort() {
  groupOrdering.value = true
  try {
    const { data } = await api.groupOrder()
    sortEditor.value.openGroups(data)
  }
  catch { /* Request client displays read errors. */ }
  finally { groupOrdering.value = false }
}
async function groupsSorted(ids) {
  // Sorting uses all categories; follow the current selection to its new page.
  groupKeyword.value = ''
  const index = ids.indexOf(selected.value?.id)
  groupPage.value = index < 0
    ? 1
    : Math.floor(index / 10) + 1
  await loadGroups()
}
async function refreshCache() {
  refreshing.value = true
  try {
    await api.refresh()
    await loadGroups()
    if (selected.value)
      await loadItems()
    $message.success(t('参数缓存已刷新'))
  }
  catch { /* Request client displays refresh errors. */ }
  finally { refreshing.value = false }
}
function focusWorkbenchDimensions() {
  groupKeyword.value = workbenchDimensionGroup
  return loadGroups(true)
}

onMounted(() => route.query.groupCode === workbenchDimensionGroup
  ? focusWorkbenchDimensions()
  : loadGroups())
watch(() => route.query.groupCode, (groupCode) => {
  if (route.name === 'ParamMgt' && groupCode === workbenchDimensionGroup)
    focusWorkbenchDimensions()
})
onBeforeUnmount(() => {
  groupRequests.invalidate()
  itemRequests.invalidate()
})
</script>

<style scoped>
.param-workspace {
  height: 100%;
  min-height: 550px;
}
.param-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
  padding: 0 4px;
}
.group-panel {
  padding-right: 14px;
}
.item-panel {
  padding-left: 14px;
}
.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 26px;
  margin-bottom: 16px;
  font-size: 16px;
  font-weight: 500;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 16px;
}
.table-area {
  flex: 1;
  min-height: 120px;
  overflow: auto;
}
.table-footer {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
  min-height: 46px;
}
:deep(.selected-param td) {
  background: color-mix(in srgb, var(--n-progressing-color, #316c72) 14%, transparent) !important;
}
:deep(.selected-param td:first-child) {
  box-shadow: inset 3px 0 #316c72;
}
@media (max-width: 999px) {
  .param-workspace {
    min-height: 950px;
  }
  .group-panel {
    padding: 0 0 14px;
  }
  .item-panel {
    padding: 14px 0 0;
  }
}
</style>
