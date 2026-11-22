<template>
  <CommonPage>
    <template #action>
      <NButton v-if="activeTab === 'uploads' && can('DocumentCenterUpload')" type="primary" :disabled="!optionsReady" @click="editor = { mode: 'create', initial: null }">
        <template #icon>
          <i class="i-fe:upload" aria-hidden="true" />
        </template>
        {{ t('上传文档') }}
      </NButton>
    </template>

    <n-tabs v-model:value="activeTab" type="line">
      <n-tab-pane name="uploads" :tab="t('用户上传')" display-directive="show">
        <n-form size="small" class="document-filters" @submit.prevent="search">
          <div class="document-filter-grid">
            <n-form-item :label="t('文档名称')" :show-feedback="false">
              <n-input v-model:value="filters.keyword" :input-props="{ 'aria-label': t('文档名称') }" :maxlength="255" clearable :placeholder="t('搜索文档名称')" />
            </n-form-item>
            <n-form-item :label="t('部门')" :show-feedback="false">
              <n-select v-model:value="filters.departmentId" :input-props="{ 'aria-label': t('部门') }" :options="departmentOptions" :loading="optionsLoading" filterable clearable :placeholder="t('全部')" />
            </n-form-item>
            <n-form-item :label="t('产品')" :show-feedback="false">
              <n-select v-model:value="filters.productId" :input-props="{ 'aria-label': t('产品') }" :options="productOptions" :loading="optionsLoading" filterable clearable :placeholder="t('全部')" />
            </n-form-item>
          </div>
          <n-collapse-transition :show="advanced">
            <div id="document-dimension-filters" class="document-filter-grid mt-12">
              <n-form-item v-for="dimension in options.dimensions" :key="dimension.slot" :label="dimensionLabel(dimension)" :show-feedback="false">
                <n-select
                  v-if="dimension.inputType === 'SELECT'"
                  v-model:value="filters.dimensions[dimension.slot]"
                  :input-props="{ 'aria-label': dimensionLabel(dimension) }"
                  :options="dimensionSelectOptions(dimension)"
                  filterable
                  clearable
                  :placeholder="t('全部')"
                />
                <n-input
                  v-else
                  :value="filters.dimensions[dimension.slot] ?? ''"
                  :input-props="{ 'aria-label': dimensionLabel(dimension) }"
                  :maxlength="255"
                  clearable
                  @update:value="filters.dimensions[dimension.slot] = $event"
                />
              </n-form-item>
            </div>
          </n-collapse-transition>
          <div class="mt-12 flex flex-wrap items-center gap-10">
            <NButton type="primary" attr-type="submit">
              {{ t('搜索') }}
            </NButton>
            <NButton @click="reset">
              {{ t('重置') }}
            </NButton>
            <NButton
              v-if="options.dimensions.length"
              text
              aria-controls="document-dimension-filters"
              :aria-expanded="advanced"
              @click="advanced = !advanced"
            >
              {{ t('维度筛选') }}
              <i :class="advanced ? 'i-fe:chevron-up' : 'i-fe:chevron-down'" class="ml-4" aria-hidden="true" />
            </NButton>
          </div>
        </n-form>

        <n-alert v-if="optionsFailed" type="error" class="mb-12">
          {{ t('文档选项加载失败') }}
          <NButton text @click="loadOptions">
            {{ t('重试') }}
          </NButton>
        </n-alert>
        <n-alert v-if="listFailed" type="error" class="mb-12">
          {{ t('加载失败') }}
          <NButton text @click="loadDocuments">
            {{ t('重试') }}
          </NButton>
        </n-alert>
        <n-data-table
          :size="appStore.tableSize"
          :columns="columns"
          :data="documents"
          :row-key="row => row.id"
          :loading="loading"
          :scroll-x="1260"
        >
          <template #empty>
            <n-empty v-if="!loading && !listFailed" :description="t('暂无文档')" />
          </template>
        </n-data-table>
        <div v-if="total" class="mt-12 flex flex-wrap items-center justify-between gap-8">
          <span>{{ t('共 {0} 条数据', total) }}</span>
          <n-pagination :page="page" :page-size="pageSize" :item-count="total" :page-slot="5" @update:page="changePage" />
        </div>

        <DocumentEditor
          v-if="editor"
          :mode="editor.mode"
          :initial="editor.initial"
          :options="options"
          @close="editor = null"
          @saved="saved"
        />
        <DocumentDetails
          v-if="selected !== null"
          :id="selected"
          :key="selected"
          :options="options"
          :can-download="can('DocumentCenterDownload')"
          :downloading="downloading"
          @close="selected = null"
          @download="download"
        />
      </n-tab-pane>
      <n-tab-pane v-if="generationFilesAccess.types.length" name="generation" :tab="t('文档生成')" display-directive="show:lazy">
        <GeneratedFiles :access="generationFilesAccess" />
      </n-tab-pane>
    </n-tabs>
  </CommonPage>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { DocumentOptions, DocumentRecord, DocumentVersion } from './api'
import { NButton } from 'naive-ui'
import { computed, defineAsyncComponent, h, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import CommonPage from '@/components/common/CommonPage.vue'
import TableActions from '@/components/common/TableActions.vue'
import { t } from '@/i18n'
import { useAppStore, usePermissionStore, useUserStore } from '@/store'
import { formatDateTime } from '@/utils'
import { downloadBlob } from '@/utils/downloadBlob'
import api from './api'
import DocumentDetails from './DocumentDetails.vue'
import DocumentEditor from './DocumentEditor.vue'
import { buildDocumentQuery, dimensionLabel, dimensionSelectOptions, documentFileAccess, documentOptionName, documentSelectOptions, emptyFilters } from './model'
import SftpPathCell from './SftpPathCell.vue'

defineOptions({ name: 'DocumentCenterView' })
const GeneratedFiles = defineAsyncComponent(() => import('./GeneratedFiles.vue'))

type DocumentAction = 'DocumentCenterUpload' | 'DocumentCenterEdit' | 'DocumentCenterDownload' | 'DocumentCenterDelete'
type Editor = { mode: 'create', initial: null } | { mode: 'edit' | 'version', initial: DocumentRecord }

const route = useRoute()
const user = useUserStore()
const appStore = useAppStore()
const permissions = usePermissionStore()
const activeTab = ref<'uploads' | 'generation'>('uploads')
const options = ref<DocumentOptions>({ departments: [], products: [], dimensions: [] })
const optionsReady = ref(false)
const optionsFailed = ref(false)
const optionsLoading = ref(false)
const documents = ref<DocumentRecord[]>([])
const filters = ref(emptyFilters())
const advanced = ref(false)
const page = ref(1)
const pageSize = 20
const total = ref(0)
const loading = ref(false)
const listFailed = ref(false)
const editor = ref<Editor | null>(null)
const selected = ref<number | null>(null)
const downloading = ref<number | null>(null)
let applied = emptyFilters()
let listRequest: AbortController | undefined

const generationFilesAccess = computed(() => {
  const resources = permissions.resourcesByCode
  const hasGenerationView = permissions.hasRoute('/business/document-generation') || resources.get('DocumentGenerationView')?.enable === true
  const actions = ['DocumentGenerationTemplateManage', 'DocumentGenerationReadFile'].filter(code => resources.get(code)?.enable === true)
  return documentFileAccess(user.currentRole.code, hasGenerationView, actions)
})
watch(generationFilesAccess, (access) => {
  if (!access.types.length)
    activeTab.value = 'uploads'
})

const departmentOptions = computed(() => documentSelectOptions(options.value.departments))
const productOptions = computed(() => documentSelectOptions(options.value.products))

function can(code: DocumentAction) {
  const buttons = route.meta.btns
  return user.currentRole.code === 'SUPER_ADMIN'
    || (Array.isArray(buttons) && buttons.some(button => button.code === code))
}

const columns = computed<DataTableColumns<DocumentRecord>>(() => [
  {
    title: t('文档名称'),
    key: 'name',
    minWidth: 210,
    ellipsis: { tooltip: true },
    render: row => h(NButton, {
      text: true,
      type: 'primary',
      class: 'document-name',
      onClick: () => selected.value = row.id,
    }, () => row.name),
  },
  {
    title: t('SFTP 相对路径'),
    key: 'sftpPath',
    width: 228,
    render: row => h(SftpPathCell, { path: row.sftpPath }),
  },
  {
    title: t('部门'),
    key: 'departmentId',
    minWidth: 125,
    ellipsis: { tooltip: true },
    render: row => documentOptionName(options.value.departments, row.departmentId),
  },
  {
    title: t('产品'),
    key: 'productId',
    minWidth: 125,
    ellipsis: { tooltip: true },
    render: row => documentOptionName(options.value.products, row.productId),
  },
  {
    title: t('最新版本'),
    key: 'latestVersion',
    width: 125,
    ellipsis: { tooltip: true },
    render: row => row.latestVersion?.versionNo ?? '—',
  },
  {
    title: t('更新时间'),
    key: 'updatedAt',
    width: 175,
    render: row => formatDateTime(row.updatedAt),
  },
  {
    title: t('操作'),
    key: 'actions',
    width: 200,
    align: 'right',
    render: row => h(TableActions, {
      actions: actionsFor(row),
      iconOnly: true,
    }),
  },
])

function actionsFor(row: DocumentRecord) {
  const actions: InstanceType<typeof TableActions>['$props']['actions'][number][] = []
  const version = row.latestVersion
  if (can('DocumentCenterDownload') && version) {
    actions.push({
      key: 'download',
      label: t('下载最新版本'),
      icon: 'i-fe:download text-14',
      secondary: true,
      disabled: downloading.value !== null,
      loading: downloading.value === version.id,
      onClick: () => download(row.id, version, true),
    })
  }
  if (can('DocumentCenterEdit')) {
    actions.push({
      key: 'edit',
      label: t('编辑'),
      icon: 'i-fe:edit-2 text-14',
      secondary: true,
      disabled: !optionsReady.value,
      onClick: () => editor.value = { mode: 'edit', initial: row },
    })
  }
  if (can('DocumentCenterUpload')) {
    actions.push({
      key: 'version',
      label: t('上传新版本'),
      icon: 'i-fe:upload text-14',
      secondary: true,
      onClick: () => editor.value = { mode: 'version', initial: row },
    })
  }
  if (can('DocumentCenterDelete')) {
    actions.push({
      key: 'delete',
      label: t('删除'),
      icon: 'i-fe:trash-2 text-14',
      type: 'error',
      secondary: true,
      onClick: () => confirmDelete(row),
    })
  }
  return actions
}

async function loadOptions() {
  if (optionsLoading.value)
    return
  optionsLoading.value = true
  optionsFailed.value = false
  try {
    options.value = (await api.options()).data
    optionsReady.value = true
  }
  catch {
    optionsFailed.value = true
  }
  finally {
    optionsLoading.value = false
  }
}

async function loadDocuments() {
  listRequest?.abort()
  const controller = new AbortController()
  listRequest = controller
  loading.value = true
  listFailed.value = false
  try {
    const { data } = await api.page(buildDocumentQuery(applied, page.value, pageSize), controller.signal)
    if (controller.signal.aborted)
      return
    const lastPage = Math.max(1, Math.ceil(data.total / pageSize))
    if (page.value > lastPage) {
      page.value = lastPage
      await loadDocuments()
      return
    }
    documents.value = data.records
    total.value = data.total
  }
  catch {
    if (!controller.signal.aborted) {
      documents.value = []
      total.value = 0
      listFailed.value = true
    }
  }
  finally {
    if (!controller.signal.aborted)
      loading.value = false
  }
}

function changePage(value: number) {
  page.value = value
  void loadDocuments()
}

function search() {
  applied = {
    ...filters.value,
    dimensions: { ...filters.value.dimensions },
  }
  changePage(1)
}

function reset() {
  filters.value = emptyFilters()
  search()
}

function saved(mode: Editor['mode']) {
  editor.value = null
  window.$message.success(t('操作成功'))
  changePage(mode === 'create'
    ? 1
    : page.value)
}

async function download(documentId: number, version: DocumentVersion, latest = false) {
  if (downloading.value !== null)
    return
  downloading.value = version.id
  try {
    const { file, name } = await api.download(documentId, latest ? undefined : version.id)
    downloadBlob(file, name ?? version.downloadName)
  }
  catch (failure) {
    if (failure instanceof Error)
      window.$message.warning(t('文档下载失败'))
  }
  finally {
    downloading.value = null
  }
}

function confirmDelete(row: DocumentRecord) {
  const dialog = window.$dialog.warning({
    title: t('删除文档'),
    content: t('确定删除文档「{0}」及其所有版本？', row.name),
    positiveText: t('删除'),
    negativeText: t('取消'),
    maskClosable: false,
    closable: false,
    closeOnEsc: false,
    onNegativeClick: () => !dialog.loading,
    async onPositiveClick() {
      if (dialog.loading)
        return false
      dialog.loading = true
      try {
        await api.remove(row.id)
        window.$message.success(t('操作成功'))
        void loadDocuments()
      }
      catch {
        return false
      }
      finally {
        dialog.loading = false
      }
    },
  })
}

onMounted(() => {
  void loadOptions()
  void loadDocuments()
})
onBeforeUnmount(() => listRequest?.abort())
</script>

<style scoped>
.document-filters {
  margin-block-end: 18px;
}

.document-filter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 180px), 1fr));
  gap: 12px 16px;
}

:deep(.document-name) {
  display: inline;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.document-name .n-button__content) {
  display: inline;
}
</style>
