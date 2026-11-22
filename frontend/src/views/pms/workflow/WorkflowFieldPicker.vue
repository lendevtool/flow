<template>
  <n-drawer :show="true" show-mask="transparent" width="min(680px, 100vw)" :aria-label="t('从字段管理选择')" @update:show="!$event && emit('close')">
    <n-drawer-content :title="t('从字段管理选择')" closable>
      <n-space vertical :size="12">
        <form class="field-search" @submit.prevent="load(1)">
          <n-input v-model:value="keyword" clearable :maxlength="100" :placeholder="t('按名称或编码搜索')" />
          <n-button attr-type="submit">
            {{ t('搜索') }}
          </n-button>
        </form>
        <n-data-table
          :columns="columns"
          :data="rows"
          :loading="loading"
          :row-key="row => row.config.code"
          :checked-row-keys="checked"
          :scroll-x="550"
          size="small"
          @update:checked-row-keys="selectPage"
        />
        <n-pagination :page="page" :item-count="total" :page-size="20" @update:page="load" />
      </n-space>
      <template #footer>
        <n-space align="center">
          <n-text depth="3">
            {{ t('已选 {0} 个字段', checked.length) }}
          </n-text>
          <n-button @click="emit('close')">
            {{ t('取消') }}
          </n-button>
          <n-button type="primary" :disabled="checked.length > 100" @click="confirm">
            {{ t('确认选择') }}
          </n-button>
        </n-space>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { FieldDefinition } from '@/views/pms/field/api'
import { computed, onMounted, ref } from 'vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import { workflowDefinitions } from './api'
import { fieldTypeLabel } from './presentation'

const props = defineProps<{ selected: string[] }>()
const emit = defineEmits<{ close: [], confirm: [codes: string[], fields: FieldDefinition[]] }>()
const keyword = ref('')
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const rows = ref<FieldDefinition[]>([])
const checked = ref([...props.selected])
const known = new Map<string, FieldDefinition>()
let requestId = 0

const columns = computed<DataTableColumns<FieldDefinition>>(() => [
  { type: 'selection', width: 42 },
  {
    title: t('字段名称'),
    key: 'name',
    minWidth: 160,
    ellipsis: { tooltip: true },
    render: ({ config }) => displayName(config.name, config.nameEn),
  },
  {
    title: t('编码'),
    key: 'code',
    width: 185,
    ellipsis: { tooltip: true },
    render: ({ config }) => config.code,
  },
  {
    title: t('字段类型'),
    key: 'type',
    width: 145,
    ellipsis: { tooltip: true },
    render: ({ config }) => fieldTypeLabel(config.dataType),
  },
])

async function load(nextPage: number) {
  const current = ++requestId
  loading.value = true
  try {
    const { data } = await workflowDefinitions.fields(keyword.value.trim(), nextPage)
    if (current === requestId) {
      rows.value = data.pageData
      total.value = data.total
      page.value = nextPage
      for (const row of data.pageData)
        known.set(row.config.code, row)
    }
  }
  catch {
    // 请求错误由统一拦截器显示。
  }
  finally {
    if (current === requestId)
      loading.value = false
  }
}

function selectPage(keys: (string | number)[]) {
  const next = new Set(keys.map(String))
  const visible = new Set(rows.value.map(row => row.config.code))
  // 保留其他页的选择和已有字段的顺序，新增字段追加到末尾。
  const retained = checked.value.filter(code => !visible.has(code) || next.has(code))
  checked.value = [
    ...new Set([
      ...retained,
      ...next,
    ]),
  ]
}

function confirm() {
  const fields = checked.value.flatMap((code) => {
    const row = known.get(code)
    return row
      ? [row]
      : []
  })
  emit('confirm', checked.value, fields)
}

onMounted(() => load(1))
</script>

<style scoped>
.field-search {
  display: flex;
  gap: 8px;
}
</style>
