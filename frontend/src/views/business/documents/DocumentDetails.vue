<template>
  <n-drawer :show="true" width="min(840px, 100vw)" @update:show="show => !show && emit('close')">
    <n-drawer-content :title="t('文档详情')" closable>
      <n-alert v-if="failed" type="error" class="mb-12">
        {{ t('加载失败') }}
        <NButton text @click="load">
          {{ t('重试') }}
        </NButton>
      </n-alert>
      <n-spin :show="loading" class="min-h-80">
        <template v-if="record">
          <n-descriptions :column="2" :title="record.name" label-placement="top" bordered size="small" class="document-properties">
            <n-descriptions-item :label="t('部门')">
              {{ documentOptionName(options.departments, record.departmentId) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('产品')">
              {{ documentOptionName(options.products, record.productId) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('创建时间')">
              {{ formatDateTime(record.createdAt) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('更新时间')">
              {{ formatDateTime(record.updatedAt) }}
            </n-descriptions-item>
            <n-descriptions-item v-for="field in fields" :key="field.slot" :label="field.label">
              {{ field.value }}
            </n-descriptions-item>
          </n-descriptions>

          <h3 class="mb-12 mt-24">
            {{ t('历史版本') }}
          </h3>
          <n-data-table
            size="small"
            :columns="columns"
            :data="record.versions"
            :row-key="version => version.id"
            :scroll-x="canDownload ? 790 : 706"
          >
            <template #empty>
              <n-empty :description="t('暂无可用版本')" />
            </template>
          </n-data-table>
        </template>
      </n-spin>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { DocumentOptions, DocumentRecord, DocumentVersion } from './api'
import { NButton } from 'naive-ui'
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue'
import { t } from '@/i18n'
import { formatDateTime } from '@/utils'
import api from './api'
import { dimensionLabel, dimensionSlots, dimensionValue, documentOptionName, formatDocumentSize } from './model'
import SftpPathCell from './SftpPathCell.vue'

const props = defineProps<{
  id: number
  options: DocumentOptions
  canDownload: boolean
  downloading: number | null
}>()

const emit = defineEmits<{
  close: []
  download: [documentId: number, version: DocumentVersion]
}>()

const record = ref<DocumentRecord | null>(null)
const loading = ref(false)
const failed = ref(false)
let detailRequest: AbortController | undefined

const fields = computed(() => dimensionSlots.flatMap((slot) => {
  const value = record.value?.dimensions[slot]
  if (!value)
    return []
  const dimension = props.options.dimensions.find(item => item.slot === slot)
  return [
    {
      slot,
      label: dimension
        ? dimensionLabel(dimension)
        : t('维度 {0}', slot),
      value: dimension
        ? dimensionValue(dimension, value)
        : value,
    },
  ]
}))

const columns = computed<DataTableColumns<DocumentVersion>>(() => {
  const result: DataTableColumns<DocumentVersion> = [
    {
      title: t('版本号'),
      key: 'versionNo',
      width: 106,
      ellipsis: { tooltip: true },
    },
    {
      title: t('原始文件名'),
      key: 'originalFileName',
      minWidth: 140,
      ellipsis: { tooltip: true },
    },
    {
      title: t('SFTP 相对路径'),
      key: 'sftpPath',
      width: 228,
      render: version => h(SftpPathCell, { path: version.sftpPath }),
    },
    {
      title: t('文件大小'),
      key: 'sizeBytes',
      width: 90,
      render: version => formatDocumentSize(version.sizeBytes),
    },
    {
      title: t('上传时间'),
      key: 'createdAt',
      width: 165,
      render: version => formatDateTime(version.createdAt),
    },
  ]
  if (props.canDownload) {
    result.push({
      title: t('操作'),
      key: 'actions',
      width: 84,
      align: 'right',
      render: version => h(NButton, {
        size: 'small',
        disabled: props.downloading !== null,
        loading: props.downloading === version.id,
        onClick: () => emit('download', props.id, version),
      }, () => t('下载')),
    })
  }
  return result
})

async function load() {
  detailRequest?.abort()
  const controller = new AbortController()
  detailRequest = controller
  loading.value = true
  failed.value = false
  try {
    const { data } = await api.detail(props.id, controller.signal)
    if (!controller.signal.aborted)
      record.value = data
  }
  catch {
    if (!controller.signal.aborted)
      failed.value = true
  }
  finally {
    if (!controller.signal.aborted)
      loading.value = false
  }
}

onMounted(load)
onBeforeUnmount(() => detailRequest?.abort())
</script>

<style scoped>
.document-properties {
  overflow-wrap: anywhere;
}
</style>
