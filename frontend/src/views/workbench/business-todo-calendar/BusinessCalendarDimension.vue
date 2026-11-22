<template>
  <n-form-item :label="dimension.label">
    <div class="min-w-164">
      <n-select
        class="max-w-340"
        clearable
        :filterable="!dimension.searchable"
        :multiple="dimension.multiple"
        :max-tag-count="2"
        :value="dimension.multiple ? values : values[0] ?? null"
        :options="options"
        :fallback-option="fallbackOption"
        :placeholder="t('不限')"
        @update:value="update"
      />
      <template v-if="dimension.searchable">
        <n-input v-model:value="keyword" clearable size="small" class="mt-6" :placeholder="t('搜索筛选选项')" />
        <n-spin :show="remote.loading.value">
          <n-alert v-if="remote.error.value" type="error" class="mt-6">
            {{ remote.error.value }}
            <n-button text @click="load">
              {{ t('重试') }}
            </n-button>
          </n-alert>
          <n-pagination v-else-if="remote.data.value && remote.data.value.total > 20" v-model:page="page" size="small" class="mt-6" :page-size="20" :item-count="remote.data.value.total" />
        </n-spin>
      </template>
    </div>
  </n-form-item>
</template>

<script setup lang="ts">
import type { BusinessDimension, BusinessOption, Page } from './api'
import { computed, ref, watch } from 'vue'
import { t } from '@/i18n'
import { useWorkbenchRequest } from '../useWorkbench'

const props = defineProps<{
  dimension: BusinessDimension
  values: string[]
  selectedOptions: BusinessOption[]
  lookup: (key: string, keyword: string, page: number) => Promise<{ data: Page<BusinessOption> }>
}>()
const emit = defineEmits<{
  update: [values: string[]]
}>()

const keyword = ref('')
const page = ref(1)
const remote = useWorkbenchRequest<Page<BusinessOption> | null>(null)
const options = computed(() => {
  const visible = props.dimension.searchable
    ? remote.data.value?.items ?? []
    : props.dimension.options
  const byValue = new Map(visible.map(item => [item.value, item]))
  for (const option of props.selectedOptions) {
    if (!byValue.has(option.value))
      byValue.set(option.value, option)
  }
  // 授权选项中的历史停用值仍可筛选。
  return [
    ...byValue.values(),
  ].map(({ value, label, enabled }) => ({
    value,
    label: enabled
      ? label
      : `${label}（${t('已停用')}）`,
  }))
})

function fallbackOption(value: string | number) {
  return {
    value,
    label: t('所选值'),
  }
}

function update(value: string | string[] | null) {
  const values = Array.isArray(value)
    ? value
    : value
      ? [value]
      : []
  emit('update', values.slice(0, props.dimension.multiple ? 50 : 1))
}

function load() {
  if (props.dimension.searchable)
    return remote.run(() => props.lookup(props.dimension.key, keyword.value.trim(), page.value))
}

watch(keyword, () => {
  page.value = 1
}, { flush: 'sync' })
watch([keyword, page], () => {
  remote.reset()
  load()
}, { immediate: true })
</script>
