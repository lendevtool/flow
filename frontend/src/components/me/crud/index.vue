<template>
  <div class="flex flex-col overflow-hidden" :class="{ 'h-full': flexHeight }">
    <AppCard v-if="$slots.default" bordered bg="#fafafc dark:black" class="mb-30 min-h-60 rounded-4">
      <form class="me-crud-query flex justify-between p-16" @submit.prevent="handleSearch()">
        <n-scrollbar x-scrollable>
          <n-space :wrap="!expand || isExpanded" :size="[32, 16]" class="p-10">
            <slot />
          </n-space>
        </n-scrollbar>
        <div class="me-crud-query-actions flex-shrink-0 p-10">
          <n-button ghost type="primary" @click="handleReset">
            <i class="i-fe:rotate-ccw mr-4" />
            {{ t('重置') }}
          </n-button>
          <n-button attr-type="submit" class="ml-20" type="primary" :disabled="queryDisabled">
            <i class="i-fe:search mr-4" />
            {{ t('搜索') }}
          </n-button>

          <template v-if="expand">
            <n-button v-if="!isExpanded" type="primary" text @click="toggleExpand">
              <i class="i-fe:chevrons-down ml-4" />
              {{ t('展开') }}
            </n-button>
            <n-button v-else text type="primary" @click="toggleExpand">
              <i class="i-fe:chevrons-up ml-4" />
              {{ t('收起') }}
            </n-button>
          </template>
        </div>
      </form>
    </AppCard>

    <slot name="toolbar" />

    <NDataTable
      :size="appStore.tableSize"
      :remote="remote"
      :loading="loading"
      :scroll-x="scrollX"
      :columns="columns"
      :data="tableData"
      :row-key="(row) => row[rowKey]"
      :checked-row-keys="checkedRowKeys"
      :pagination="isPagination ? pagination : false"
      :flex-height="flexHeight"
      :class="{ 'flex-1': flexHeight }"
      @update:checked-row-keys="onChecked"
      @update:page="onPageChange"
    >
      <template v-if="$slots.empty" #empty>
        <slot name="empty" :loading="loading" />
      </template>
    </NDataTable>
  </div>
</template>

<script setup>
import { NDataTable } from 'naive-ui'
import { utils, writeFile } from 'xlsx'
import { t } from '@/i18n'
import { useAppStore } from '@/store'

const props = defineProps({
  /**
   * @remote true: 后端分页  false： 前端分页
   */
  remote: {
    type: Boolean,
    default: true,
  },
  /**
   * @isPagination 是否分页
   */
  isPagination: {
    type: Boolean,
    default: true,
  },
  pageSize: {
    type: Number,
    default: 10,
  },
  scrollX: {
    type: Number,
    default: 1200,
  },
  flexHeight: {
    type: Boolean,
    default: true,
  },
  rowKey: {
    type: String,
    default: 'id',
  },
  checkedRowKeys: Array,
  columns: {
    type: Array,
    required: true,
  },
  /** queryBar中的参数 */
  queryItems: {
    type: Object,
    default() {
      return {}
    },
  },
  /**
   * ! 约定接口入参出参
   * 分页模式需约定分页接口入参
   *    @pageSize 分页参数：一页展示多少条，默认10
   *    @pageNo   分页参数：页码，默认1
   * 需约定接口出参
   *    @pageData 分页模式必须,非分页模式如果没有pageData则取上一层data
   *    @total    分页模式必须，非分页模式如果没有total则取上一层data.length
   */
  getData: {
    type: Function,
    required: true,
  },
  /** 是否支持展开 */
  expand: Boolean,
  /** 查询条件未通过校验时，保留当前结果并暂停请求。 */
  queryDisabled: Boolean,
})

const emit = defineEmits([
  'update:queryItems',
  'onChecked',
  'onDataChange',
  'update:queryFailed',
])
const appStore = useAppStore()
const loading = ref(false)
let queryId = 0
const initQuery = { ...props.queryItems }
const tableData = ref([])
const pagination = reactive({
  page: 1,
  pageSize: props.pageSize,
  prefix({ itemCount }) {
    return t('共 {0} 条数据', itemCount)
  },
})

// 是否展开
const isExpanded = ref(false)

function toggleExpand() {
  isExpanded.value = !isExpanded.value
}

async function handleQuery() {
  if (props.queryDisabled)
    return
  const id = ++queryId
  loading.value = true
  emit('update:queryFailed', false)
  try {
    let paginationParams = {}
    // 如果非分页模式或者使用前端分页,则无需传分页参数
    if (props.isPagination && props.remote) {
      paginationParams = { pageNo: pagination.page, pageSize: pagination.pageSize }
    }
    const { data } = await props.getData({
      ...props.queryItems,
      ...paginationParams,
    })
    if (id !== queryId)
      return
    tableData.value = data?.pageData || data
    pagination.itemCount = data.total ?? data.length
    const lastPage = Math.max(1, Math.ceil(pagination.itemCount / pagination.pageSize))
    if (props.remote && !tableData.value.length && pagination.page > lastPage)
      return onPageChange(lastPage)
  }
  catch (error) {
    if (id !== queryId)
      return
    emit('update:queryFailed', true)
    console.error(error)
    tableData.value = []
    pagination.itemCount = 0
  }
  finally {
    if (id === queryId) {
      emit('onDataChange', tableData.value)
      loading.value = false
    }
  }
}

function handleSearch(keepCurrentPage = false) {
  if (keepCurrentPage || !props.remote)
    return handleQuery()
  return onPageChange(1)
}
async function handleReset() {
  const queryItems = { ...props.queryItems }
  for (const key in queryItems) {
    queryItems[key] = null
  }
  emit('update:queryItems', {
    ...queryItems,
    ...initQuery,
  })
  await nextTick()
  pagination.page = 1
  handleQuery()
}
function onPageChange(currentPage) {
  if (props.queryDisabled)
    return
  pagination.page = currentPage
  if (props.remote)
    return handleQuery()
}
function onChecked(rowKeys) {
  if (props.columns.some(item => item.type === 'selection')) {
    emit('onChecked', rowKeys)
  }
}
function handleExport(columns = props.columns, data = tableData.value) {
  if (!data?.length)
    return $message.warning(t('没有数据'))
  const columnsData = columns.filter(item => !!item.title && !item.hideInExcel)
  const thKeys = columnsData.map(item => item.key)
  const thData = columnsData.map(item => item.title)
  const trData = data.map(item => thKeys.map(key => item[key]))
  const sheet = utils.aoa_to_sheet([thData, ...trData])
  const workBook = utils.book_new()
  utils.book_append_sheet(workBook, sheet, t('数据报表'))
  writeFile(workBook, t('数据报表.xlsx'))
}

defineExpose({
  handleSearch,
  handleReset,
  handleExport,
})
</script>
