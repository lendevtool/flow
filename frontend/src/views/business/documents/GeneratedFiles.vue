<template>
  <div class="generated-files flex flex-col">
    <n-alert v-if="failed" type="error" class="mb-12 shrink-0">
      {{ t('加载失败') }}
      <n-button text @click="table?.handleSearch(true)">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <MeCrud
      ref="table"
      v-model:query-items="filters"
      :columns="columns"
      :get-data="load"
      :page-size="20"
      :scroll-x="hasActions ? 1050 : 930"
      class="min-h-0 flex-1"
      @update:query-failed="failed = $event"
    >
      <MeQueryItem :label="t('文档类型')" :content-width="160">
        <n-select
          :value="selectedType"
          :options="typeOptions"
          :input-props="{ 'aria-label': t('文档类型') }"
          @update:value="changeType"
        />
      </MeQueryItem>
      <MeQueryItem :label="t('关键词')" :content-width="220">
        <n-input
          v-model:value="filters.keyword"
          :input-props="{ 'aria-label': t('关键词') }"
          :placeholder="t('搜索文件名、模板名或模板编码')"
          :maxlength="200"
          clearable
        />
      </MeQueryItem>
      <template #empty="{ loading }">
        <n-empty v-if="!loading && !failed" :description="t('暂无文档')" />
      </template>
    </MeCrud>
  </div>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { DocumentGenerationFile, DocumentGenerationFileType } from '../document-generation/api'
import type { DocumentFileAccess } from './model'
import { NTag } from 'naive-ui'
import { computed, h, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import MeQueryItem from '@/components/me/crud/QueryItem.vue'
import { t } from '@/i18n'
import { formatDateTime } from '@/utils'
import { downloadBlob } from '@/utils/downloadBlob'
import api, { downloadGeneratedDocument, errorText } from '../document-generation/api'
import { statusLabel } from '../document-generation/schema'
import { formatDocumentSize } from './model'
import SftpPathCell from './SftpPathCell.vue'

interface Filters {
  type: DocumentGenerationFileType | null
  keyword: string | null
}

const props = defineProps<{ access: DocumentFileAccess }>()
const table = ref<InstanceType<typeof MeCrud>>()
const filters = ref<Filters>({ type: null, keyword: null })
const failed = ref(false)
const downloading = ref<string | null>(null)
let listRequest: AbortController | undefined
let downloadRequest: AbortController | undefined

const typeOptions = computed(() => props.access.types.map(type => ({
  value: type,
  label: t(type === 'TEMPLATE_VERSION'
    ? '文档模板'
    : '生成成品'),
})))

function availableType(type: DocumentGenerationFileType | null) {
  return type && props.access.types.includes(type)
    ? type
    : props.access.types[0] ?? null
}

const selectedType = computed(() => availableType(filters.value.type))
const hasActions = computed(() => props.access.types.includes('TEMPLATE_VERSION') || props.access.canDownloadGenerations)
const columns = computed<DataTableColumns<DocumentGenerationFile>>(() => [
  {
    title: t('模板名称'),
    key: 'templateName',
    minWidth: 150,
    ellipsis: { tooltip: true },
  },
  {
    title: t('SFTP 相对路径'),
    key: 'sftpPath',
    width: 228,
    render: row => h(SftpPathCell, { path: row.sftpPath }),
  },
  {
    title: t('版本'),
    key: 'versionNo',
    width: 78,
    render: row => `v${row.versionNo}`,
  },
  {
    title: t('状态'),
    key: 'status',
    width: 102,
    render: row => h(NTag, {
      size: 'small',
      type: row.status === 'DRAFT'
        ? 'default'
        : 'success',
    }, () => statusLabel(row.status)),
  },
  {
    title: t('文件大小'),
    key: 'sizeBytes',
    width: 104,
    render: row => formatDocumentSize(row.sizeBytes),
  },
  {
    title: t('创建时间'),
    key: 'createdAt',
    width: 174,
    render: row => formatDateTime(row.createdAt),
  },
  ...(hasActions.value
    ? [
        {
          title: t('操作'),
          key: 'actions',
          width: 110,
          fixed: 'right' as const,
          render: (row: DocumentGenerationFile) => canDownload(row.type)
            ? h(TableActions, {
                actions: [
                  {
                    key: 'download',
                    label: t('下载'),
                    icon: 'i-fe:download',
                    loading: downloading.value === fileKey(row),
                    disabled: downloading.value !== null,
                    onClick: () => download(row),
                  },
                ],
              })
            : '—',
        },
      ]
    : []),
])

function fileKey(row: DocumentGenerationFile) {
  return `${row.type}:${row.generationId ?? row.templateVersionId}`
}

function canDownload(type: DocumentGenerationFileType) {
  return props.access.types.includes(type) && (type === 'TEMPLATE_VERSION' || props.access.canDownloadGenerations)
}

async function changeType(type: DocumentGenerationFileType) {
  filters.value.type = type
  await nextTick()
  void table.value?.handleSearch()
}

async function load({ type, keyword, pageNo, pageSize }: Filters & { pageNo: number, pageSize: number }) {
  listRequest?.abort()
  const source = availableType(type)
  if (!source) {
    return {
      data: { pageData: [], total: 0 },
    }
  }

  const controller = new AbortController()
  listRequest = controller
  try {
    const { data } = await api.files({
      type: source,
      keyword: keyword?.trim() || undefined,
      pageNo,
      pageSize,
    }, controller.signal)
    return {
      data: {
        pageData: data.records.map(row => ({
          ...row,
          id: fileKey(row),
        })),
        total: data.total,
      },
    }
  }
  catch {
    if (controller.signal.aborted) {
      return {
        data: { pageData: [], total: 0 },
      }
    }
    throw new Error(t('加载失败'))
  }
}

async function download(row: DocumentGenerationFile) {
  if (downloading.value !== null || !canDownload(row.type))
    return

  const controller = new AbortController()
  downloadRequest = controller
  downloading.value = fileKey(row)
  try {
    const { file, name } = await downloadGeneratedDocument(row, controller.signal)
    if (!controller.signal.aborted && canDownload(row.type))
      downloadBlob(file, name)
  }
  catch (error) {
    if (!controller.signal.aborted)
      window.$message.error(errorText(error, t('文档下载失败')))
  }
  finally {
    downloading.value = null
  }
}

watch(() => props.access.types.join(','), () => table.value?.handleSearch())
onMounted(() => table.value?.handleSearch())
onBeforeUnmount(() => {
  listRequest?.abort()
  downloadRequest?.abort()
})
</script>

<style scoped>
.generated-files {
  height: min(560px, 70vh);
}
</style>
