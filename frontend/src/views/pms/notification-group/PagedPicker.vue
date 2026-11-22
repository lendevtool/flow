<template>
  <n-modal :show="true" preset="card" :title="title" style="width: min(620px, 94vw)" @update:show="close">
    <n-space vertical :size="12">
      <n-input :value="keyword" clearable :maxlength="100" :placeholder="placeholder" :input-props="{ 'aria-label': placeholder }" @update:value="search" />
      <n-alert v-if="error" type="error">
        {{ errorMessage(error, t('加载失败')) }}
        <n-button text @click="reload">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-data-table
        :columns="columns"
        :data="data?.pageData ?? []"
        :row-key="row => row.id"
        :checked-row-keys="[...selected.keys()]"
        :loading="loading"
        :max-height="350"
        allow-checking-not-loaded
        size="small"
        @update:checked-row-keys="updateSelected"
      >
        <template #empty>
          <n-empty v-if="!loading && !error" :description="t('没有匹配的选项')" />
        </template>
      </n-data-table>
      <n-pagination v-if="total > PAGE_SIZE" v-model:page="page" :item-count="total" :page-size="PAGE_SIZE" :disabled="loading" />
    </n-space>
    <template #footer>
      <n-space justify="end">
        <n-button @click="emit('close')">
          {{ t('取消') }}
        </n-button>
        <n-button type="primary" :disabled="!selected.size" @click="emit('select', [...selected.values()])">
          {{ t('添加所选（{0}）', selected.size) }}
        </n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts" generic="T extends { id: number }">
import type { DataTableColumns, DataTableRowKey } from 'naive-ui'
import type { Page, SearchPage } from './api'
import { computed, ref, shallowRef, watch } from 'vue'
import { t } from '@/i18n'
import { errorMessage, PAGE_SIZE, selectPage } from './model'
import { useLatestResource } from './useLatestResource'

const props = defineProps<{
  title: string
  placeholder: string
  excluded: ReadonlySet<number>
  limit: number
  resultColumns: DataTableColumns<T>
  load: (params: SearchPage, signal: AbortSignal) => Promise<Page<T>>
}>()

const emit = defineEmits<{
  select: [rows: T[]]
  close: []
}>()

const page = ref(1)
const keyword = ref('')
const total = ref(0)
const selected = shallowRef<ReadonlyMap<number, T>>(new Map())
const columns = computed<DataTableColumns<T>>(() => [
  {
    type: 'selection',
    disabled: row => props.excluded.has(row.id),
  },
  ...props.resultColumns,
])
const { data, loading, error, reload } = useLatestResource(() => {
  const params = { keyword: keyword.value.trim() || undefined, pageNo: page.value, pageSize: PAGE_SIZE }
  return signal => props.load(params, signal)
})

watch(data, (result) => {
  if (result)
    total.value = result.total
})

function search(value: string) {
  keyword.value = value
  page.value = 1
  total.value = 0
}

function updateSelected(keys: DataTableRowKey[]) {
  if (!data.value)
    return
  const rows = data.value.pageData.filter(row => !props.excluded.has(row.id))
  const next = selectPage(selected.value, rows, keys, props.limit)
  if (next === selected.value)
    window.$message.warning(t('本次还可添加 {0} 项。', props.limit))
  else
    selected.value = next
}

function close(show: boolean) {
  if (!show)
    emit('close')
}
</script>
