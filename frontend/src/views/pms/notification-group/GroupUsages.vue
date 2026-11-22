<template>
  <n-card size="small" :title="t('使用场景')">
    <n-space vertical :size="12">
      <n-alert v-if="error" type="error">
        {{ errorMessage(error, t('加载失败')) }}
        <n-button text @click="reload">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-alert v-if="editing && total > 0" type="info">
        {{ t('保存群组变更会影响以下场景今后发送时的收件人。') }}
      </n-alert>
      <n-data-table v-if="!error" size="small" :columns="columns" :data="data?.pageData ?? []" :loading="loading" :row-key="row => row.sceneCode">
        <template #empty>
          <n-empty v-if="!loading" :description="t('此群组尚未用于任何场景')" />
        </template>
      </n-data-table>
      <n-pagination v-if="total > PAGE_SIZE" v-model:page="page" :item-count="total" :page-size="PAGE_SIZE" :disabled="loading" />
    </n-space>
  </n-card>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { SceneUsage } from './api'
import { computed, ref, watch } from 'vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import api from './api'
import { errorMessage, PAGE_SIZE } from './model'
import { useLatestResource } from './useLatestResource'

const props = defineProps<{ groupId: number, editing: boolean }>()
const page = ref(1)
const total = ref(0)

const columns = computed<DataTableColumns<SceneUsage>>(() => [
  {
    title: t('场景名称'),
    key: 'name',
    ellipsis: { tooltip: true },
    render: row => displayName(row.nameCn, row.nameEn),
  },
  {
    title: t('场景编码'),
    key: 'sceneCode',
    ellipsis: { tooltip: true },
  },
])
const { data, loading, error, reload } = useLatestResource(() => {
  const { groupId } = props
  const paging = { pageNo: page.value, pageSize: PAGE_SIZE }
  return async signal => (await api.usages(groupId, paging, signal)).data
})
watch(data, (result) => {
  if (result) {
    total.value = result.total
    page.value = Math.min(page.value, Math.max(1, Math.ceil(result.total / PAGE_SIZE)))
  }
})
</script>
