<template>
  <n-alert v-if="loadFailed" type="error" class="mb-12">
    {{ t('加载失败，请重试') }}
    <n-button text class="ml-8" @click="refresh">
      {{ t('重试') }}
    </n-button>
  </n-alert>
  <MeCrud
    ref="table"
    :columns="columns"
    :scroll-x="fields.length * 144 + 96"
    :page-size="20"
    :get-data="loadRecords"
    :flex-height="false"
    @update:query-failed="loadFailed = $event"
  />
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { ProductField, ProductRecord, ProductValues, RelatedTab } from './model'
import { computed, h, onMounted, ref, watch } from 'vue'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import { t } from '@/i18n'
import api from './api'
import { relatedDefinitions } from './model'

const props = defineProps<{
  productId: number
  version: number
  tab: RelatedTab
  busy: boolean
  skuOptions: readonly { label: string, value: number }[]
}>()
const emit = defineEmits<{ edit: [row: ProductRecord] }>()

const table = ref<InstanceType<typeof MeCrud> | null>(null)
const loadFailed = ref(false)
const fields = computed<ProductField[]>(() => relatedDefinitions[props.tab].fields)

const columns = computed<DataTableColumns<ProductRecord>>(() => [
  ...fields.value.map((field): DataTableColumns<ProductRecord>[number] => ({
    title: t(field.label),
    key: field.key,
    minWidth: 130,
    ellipsis: { tooltip: true },
    render: row => recordLabel(field, row.values[field.key]),
  })),
  {
    title: t('操作'),
    key: 'actions',
    width: 90,
    fixed: 'right',
    align: 'right',
    render: row => h(TableActions, {
      actions: [
        {
          key: 'edit',
          label: t('编辑'),
          disabled: props.busy,
          onClick: () => emit('edit', row),
        },
      ],
    }),
  },
])

onMounted(refresh)
watch(() => props.version, refresh)

function refresh() {
  return table.value?.handleSearch(true)
}

function loadRecords(params: Parameters<typeof api.listRecords>[2]) {
  return api.listRecords(props.productId, props.tab, params)
}

function recordLabel(field: ProductField, value: ProductValues[string]) {
  if (value === null || value === '')
    return '—'
  if (field.type === 'sku')
    return props.skuOptions.find(option => option.value === value)?.label ?? '—'
  const label = field.options?.[String(value)]
  return label
    ? t(label)
    : String(value)
}
</script>
