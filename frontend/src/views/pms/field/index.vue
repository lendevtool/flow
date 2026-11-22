<template>
  <CommonPage>
    <template #action>
      <NButton v-if="can('FieldCreate')" type="primary" :disabled="!hasAvailableTypes" @click="openEditor(null)">
        <i class="i-material-symbols:add mr-4 text-18" />
        {{ t('新增字段') }}
      </NButton>
    </template>

    <div class="h-full flex flex-col">
      <n-alert v-if="metadataFailed || listFailed" type="error" class="mb-12 shrink-0">
        {{ t('加载失败') }}
        <NButton text @click="reload">
          {{ t('重试') }}
        </NButton>
      </n-alert>
      <n-alert v-else-if="metadataLoaded && !hasAvailableTypes" type="warning" class="mb-12 shrink-0">
        {{ t('没有启用的字段类型，请在参数管理中维护。') }}
      </n-alert>
      <MeCrud
        ref="table"
        v-model:query-items="queryItems"
        :scroll-x="1100"
        :page-size="20"
        :columns="columns"
        :get-data="loadFields"
        class="min-h-0 flex-1"
        @update:query-failed="listFailed = $event"
      >
        <MeQueryItem :label="t('字段编码或名称')" :label-width="90" :content-width="220">
          <n-input v-model:value="queryItems.keyword" clearable :maxlength="100" :placeholder="t('搜索字段编码或名称')" />
        </MeQueryItem>
        <MeQueryItem :label="t('字段类型')" :label-width="64" :content-width="150">
          <n-select v-model:value="queryItems.dataType" :options="typeOptions" clearable />
        </MeQueryItem>
        <MeQueryItem :label="t('状态')" :label-width="50" :content-width="120">
          <n-select v-model:value="queryItems.enable" :options="statusOptions" clearable />
        </MeQueryItem>
      </MeCrud>
    </div>

    <FieldEditor
      v-if="editor"
      :initial="editor.row"
      :readonly="editor.readonly"
      :metadata="metadata"
      @close="editor = null"
      @saved="saved"
    />
    <FieldValidationDialog
      v-if="testing"
      :field="testing"
      :type-label="typeLabel(testing)"
      @close="testing = null"
    />
  </CommonPage>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { FieldDefinition, Metadata } from './api'
import type { DataType } from './model'
import { NButton, NTag } from 'naive-ui'
import { computed, h, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import MeQueryItem from '@/components/me/crud/QueryItem.vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import { useUserStore } from '@/store'
import api from './api'
import FieldEditor from './FieldEditor.vue'
import FieldValidationDialog from './FieldValidationDialog.vue'
import { isDataType } from './model'

defineOptions({ name: 'FieldMgt' })

const user = useUserStore()
const route = useRoute()
interface Filters {
  keyword: string | null
  dataType: DataType | null
  enable: 1 | 0 | null
}

const table = ref<InstanceType<typeof MeCrud> | null>(null)
const queryItems = ref<Filters>({ keyword: null, dataType: null, enable: null })
const listFailed = ref(false)
const metadataFailed = ref(false)
const metadataLoaded = ref(false)
const metadata = ref<Metadata>({ types: [], formats: [], characters: [] })
const editor = ref<{ row: FieldDefinition | null, readonly: boolean } | null>(null)
const testing = ref<FieldDefinition | null>(null)

const hasAvailableTypes = computed(() => metadata.value.types.some(option => option.enable && isDataType(option.code)))
const typeOptions = computed(() => metadata.value.types.map(row => ({
  value: row.code,
  label: displayName(row.name, row.nameEn),
})))
const statusOptions = computed(() => [
  {
    value: 1,
    label: t('启用'),
  },
  {
    value: 0,
    label: t('停用'),
  },
])

function can(code: string) {
  const buttons = route.meta.btns
  return user.currentRole.code === 'SUPER_ADMIN'
    || (Array.isArray(buttons) && buttons.some(button => button.code === code))
}

function typeLabel(row: FieldDefinition) {
  return typeOptions.value.find(option => option.value === row.config.dataType)?.label ?? row.config.dataType
}

const columns = computed<DataTableColumns<FieldDefinition>>(() => [
  {
    title: t('字段编码'),
    key: 'code',
    minWidth: 150,
    ellipsis: { tooltip: true },
    render: row => row.config.code,
  },
  {
    title: t('中文名称'),
    key: 'name',
    minWidth: 130,
    ellipsis: { tooltip: true },
    render: row => row.config.name,
  },
  {
    title: t('英文名称'),
    key: 'nameEn',
    minWidth: 155,
    ellipsis: { tooltip: true },
    render: row => row.config.nameEn,
  },
  {
    title: t('字段类型'),
    key: 'dataType',
    minWidth: 115,
    ellipsis: { tooltip: true },
    render: typeLabel,
  },
  {
    title: t('状态'),
    key: 'enable',
    width: 90,
    render: row => h(NTag, {
      size: 'small',
      type: row.config.enable ? 'success' : 'default',
    }, () => t(row.config.enable ? '启用' : '停用')),
  },
  {
    title: t('操作'),
    key: 'actions',
    width: 420,
    align: 'right',
    fixed: 'right',
    render: row => h(TableActions, {
      actions: actionsFor(row),
    }),
  },
])

function actionsFor(row: FieldDefinition) {
  const actions: InstanceType<typeof TableActions>['$props']['actions'][number][] = [
    {
      key: 'detail',
      label: t('详情'),
      icon: 'i-fe:eye text-14',
      secondary: true,
      onClick: () => openEditor(row, true),
    },
    {
      key: 'validate',
      label: t('校验测试'),
      icon: 'i-fe:check-circle text-14',
      type: 'primary',
      secondary: true,
      onClick: () => testing.value = row,
    },
  ]
  if (can('FieldEdit')) {
    actions.push({
      key: 'edit',
      label: t('编辑'),
      onClick: () => openEditor(row),
    })
  }
  if (can('FieldDelete')) {
    actions.push({
      key: 'delete',
      label: t('删除'),
      icon: 'i-material-symbols:delete-outline text-14',
      type: 'error',
      onClick: () => confirmRemove(row),
    })
  }
  return actions
}

async function loadMetadata() {
  metadataFailed.value = false
  try {
    metadata.value = (await api.metadata()).data
    metadataLoaded.value = true
  }
  catch {
    metadataFailed.value = true
  }
}

function loadFields(params: Filters & { pageNo: number, pageSize: number }) {
  return api.page({
    ...params,
    keyword: params.keyword?.trim() ?? '',
    enable: params.enable === null
      ? null
      : params.enable === 1,
  })
}

function reload() {
  void loadMetadata()
  void table.value?.handleSearch()
}

onMounted(reload)

function openEditor(row: FieldDefinition | null, readonly = false) {
  editor.value = { row, readonly }
}

function saved() {
  editor.value = null
  window.$message.success(t('操作成功'))
  void table.value?.handleSearch(true)
}

function confirmRemove(row: FieldDefinition) {
  let deleting = false
  window.$dialog.warning({
    title: t('删除字段'),
    content: t('确定删除字段「{0}」？', displayName(row.config.name, row.config.nameEn)),
    positiveText: t('删除'),
    negativeText: t('取消'),
    async onPositiveClick() {
      if (deleting)
        return false
      deleting = true
      try {
        await api.remove(row.id)
        window.$message.success(t('操作成功'))
        await table.value?.handleSearch(true)
      }
      catch {
        return false
      }
      finally {
        deleting = false
      }
    },
  })
}
</script>
