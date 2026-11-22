<template>
  <n-card size="small" :title="sources ? t('草稿收件人预览') : t('收件人预览')">
    <template #header-extra>
      <n-button size="small" secondary :loading="loading" @click="reload">
        {{ t('刷新') }}
      </n-button>
    </template>
    <n-space vertical :size="12">
      <div class="text-13 opacity-75">
        {{ t('按当前组织架构计算，同一用户只显示一次。') }}
      </div>
      <n-alert v-if="error" type="error">
        {{ errorMessage(error, t('加载失败')) }}
        <n-button text @click="reload">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <div v-if="data" aria-live="polite">
        {{ t('共 {0} 名收件人', data.total) }}
      </div>
      <n-data-table v-if="!error" size="small" :columns="columns" :data="data?.pageData ?? []" :loading="loading" :row-key="row => row.userId" :max-height="340">
        <template #empty>
          <n-empty v-if="!loading" :description="t('当前配置匹配 0 名收件人')" />
        </template>
      </n-data-table>
      <n-pagination v-if="total > PAGE_SIZE" v-model:page="page" :item-count="total" :page-size="PAGE_SIZE" :disabled="loading" />
    </n-space>
  </n-card>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { Recipient, Sources } from './api'
import { computed, ref, watch } from 'vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import api from './api'
import { errorMessage, PAGE_SIZE, sourceWrite } from './model'
import { useLatestResource } from './useLatestResource'

const props = defineProps<{
  groupId?: number
  sources?: Sources
}>()

const page = ref(1)
const total = ref(0)
const selection = computed(() => props.sources
  ? sourceWrite(props.sources)
  : null)
watch([
  selection,
  () => props.groupId,
], () => {
  page.value = 1
  total.value = 0
}, { flush: 'sync' })

const { data, loading, error, reload } = useLatestResource(() => {
  const groupId = props.groupId
  const draft = selection.value
  const paging = { pageNo: page.value, pageSize: PAGE_SIZE }
  if (draft) {
    return async signal => (await api.preview({
      ...draft,
      ...paging,
      groupId,
    }, signal)).data
  }
  if (groupId == null)
    return null
  return async signal => (await api.recipients(groupId, paging, signal)).data
})

watch(data, (result) => {
  if (result) {
    total.value = result.total
    page.value = Math.min(page.value, Math.max(1, Math.ceil(result.total / PAGE_SIZE)))
  }
})

const columns = computed<DataTableColumns<Recipient>>(() => [
  {
    title: t('姓名'),
    key: 'name',
    ellipsis: { tooltip: true },
    render: row => displayName(row.nameCn || row.username, row.nameEn),
  },
  {
    title: t('用户名'),
    key: 'username',
    ellipsis: { tooltip: true },
  },
])
</script>
