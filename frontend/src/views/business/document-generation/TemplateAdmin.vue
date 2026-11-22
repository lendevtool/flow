<template>
  <div class="docgen-admin flex flex-col">
    <div class="mb-12">
      <n-button type="primary" @click="openEditor(null)">
        {{ t('新建模板') }}
      </n-button>
    </div>
    <n-alert v-if="failed" type="error" class="mb-12 shrink-0">
      {{ t('模板列表加载失败') }}
      <n-button text @click="table?.handleSearch(true)">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <MeCrud ref="table" v-model:query-items="filters" :columns="columns" :get-data="load" :page-size="20" :scroll-x="900" class="min-h-0 flex-1" @update:query-failed="failed = $event">
      <MeQueryItem :label="t('模板编码或名称')" :label-width="112" :content-width="175">
        <n-input v-model:value="filters.keyword" :input-props="{ 'aria-label': t('模板编码或名称') }" :maxlength="200" clearable />
      </MeQueryItem>
      <MeQueryItem :label="t('状态')" :label-width="55" :content-width="120">
        <n-select v-model:value="filters.enabled" :options="enabledOptions" :input-props="{ 'aria-label': t('状态') }" clearable />
      </MeQueryItem>
      <MeQueryItem :label="t('发布状态')" :content-width="120">
        <n-select v-model:value="filters.published" :options="publicationOptions" :input-props="{ 'aria-label': t('发布状态') }" clearable />
      </MeQueryItem>
      <template #empty="{ loading }">
        <n-empty v-if="!loading && !failed" :description="t('暂无模板')" />
      </template>
    </MeCrud>
  </div>

  <n-modal :show="editing" preset="card" :title="t(editingRow ? '编辑模板' : '新建模板')" style="width: min(600px, 94vw)" :closable="!saving" :mask-closable="false" :close-on-esc="!saving" @update:show="show => !show && (editing = false)">
    <n-form id="docgen-template-form" :disabled="saving" label-placement="left" label-align="left" :label-width="110" @submit.prevent="save">
      <n-form-item :label="t('模板编码')" required>
        <n-input v-model:value="form.code" :input-props="{ 'aria-label': t('模板编码') }" :disabled="editingRow !== null" :maxlength="64" />
      </n-form-item>
      <n-form-item :label="t('模板名称')" required>
        <n-input v-model:value="form.name" :input-props="{ 'aria-label': t('模板名称') }" :maxlength="200" />
      </n-form-item>
      <n-form-item :label="t('说明')">
        <n-input v-model:value="form.description" :input-props="{ 'aria-label': t('说明') }" type="textarea" :maxlength="500" />
      </n-form-item>
      <n-alert v-if="saveError" type="error" class="docgen-message">
        {{ saveError }}
      </n-alert>
    </n-form>
    <template #footer>
      <n-space justify="end">
        <n-button :disabled="saving" @click="editing = false">
          {{ t('取消') }}
        </n-button>
        <n-button type="primary" attr-type="submit" form="docgen-template-form" :loading="saving" :disabled="!form.name.trim() || !form.code.trim()">
          {{ t('保存') }}
        </n-button>
      </n-space>
    </template>
  </n-modal>

  <TemplateVersions v-if="versions" :template="versions.template" :initial-version="versions.initialVersion" :can-publish="canPublish" @close="versions = null" @changed="saved" @preview="emit('preview', $event)" @download="emit('download', $event)" />
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { DocxFile, TemplateSummary, TemplateVersion } from './api'
import { NTag } from 'naive-ui'
import { computed, h, onMounted, reactive, ref } from 'vue'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import MeQueryItem from '@/components/me/crud/QueryItem.vue'
import { t } from '@/i18n'
import api, { errorText } from './api'
import TemplateVersions from './TemplateVersions.vue'

defineProps<{ canPublish: boolean }>()
const emit = defineEmits<{ changed: [], preview: [file: DocxFile], download: [file: DocxFile] }>()
const table = ref<InstanceType<typeof MeCrud>>()
const filters = ref({ keyword: null as string | null, enabled: null as string | null, published: null as string | null })
const failed = ref(false)
const editing = ref(false)
const editingRow = ref<TemplateSummary | null>(null)
const saving = ref(false)
const toggling = ref<string | null>(null)
const saveError = ref('')
const form = reactive({ code: '', name: '', description: '' })
const versions = ref<{ template: TemplateSummary, initialVersion?: TemplateVersion } | null>(null)
const enabledOptions = computed(() => [
  {
    value: 'enabled',
    label: t('启用'),
  },
  {
    value: 'disabled',
    label: t('停用'),
  },
])
const publicationOptions = computed(() => [
  {
    value: 'published',
    label: t('已发布'),
  },
  {
    value: 'unpublished',
    label: t('未发布'),
  },
])

const columns = computed<DataTableColumns<TemplateSummary>>(() => [
  {
    key: 'code',
    title: t('模板编码'),
    minWidth: 150,
    ellipsis: { tooltip: true },
  },
  {
    key: 'name',
    title: t('模板名称'),
    minWidth: 150,
    ellipsis: { tooltip: true },
  },
  {
    key: 'description',
    title: t('说明'),
    minWidth: 180,
    ellipsis: { tooltip: true },
    render: row => row.description || '—',
  },
  {
    key: 'enabled',
    title: t('状态'),
    width: 90,
    render: row => h(NTag, {
      size: 'small',
      type: row.enabled ? 'success' : 'default',
    }, () => t(row.enabled ? '启用' : '停用')),
  },
  {
    key: 'published',
    title: t('发布状态'),
    width: 105,
    render: row => t(row.currentVersionId ? '已发布' : '未发布'),
  },
  {
    key: 'actions',
    title: t('操作'),
    width: 230,
    fixed: 'right',
    render: row => h(TableActions, {
      actions: [
        {
          key: 'versions',
          label: t('版本管理'),
          type: 'primary',
          onClick: () => { versions.value = { template: row } },
        },
        {
          key: 'edit',
          label: t('编辑'),
          onClick: () => openEditor(row),
        },
        {
          key: 'toggle',
          label: t(row.enabled ? '停用' : '启用'),
          type: row.enabled ? 'error' : 'default',
          loading: toggling.value === row.id,
          disabled: toggling.value !== null,
          onClick: () => toggle(row),
        },
      ],
    }),
  },
])

async function load({ pageNo, pageSize, keyword, enabled, published }: typeof filters.value & { pageNo: number, pageSize: number }) {
  try {
    const { data } = await api.adminTemplates({
      pageNo,
      pageSize,
      keyword: keyword?.trim() || undefined,
      enabled: enabled == null
        ? undefined
        : enabled === 'enabled',
      published: published == null
        ? undefined
        : published === 'published',
    })
    return {
      data: { pageData: data.records, total: data.total },
    }
  }
  catch {
    throw new Error(t('模板列表加载失败'))
  }
}

function openEditor(row: TemplateSummary | null) {
  editingRow.value = row
  form.code = row?.code ?? ''
  form.name = row?.name ?? ''
  form.description = row?.description ?? ''
  saveError.value = ''
  editing.value = true
}

function saved() {
  void table.value?.handleSearch(true)
  emit('changed')
}

async function save() {
  const code = form.code.trim()
  const name = form.name.trim()
  if (!name || saving.value)
    return
  if (!editingRow.value && !/^[a-z0-9][a-z0-9_-]{0,63}$/.test(code)) {
    saveError.value = t('模板编码须以小写字母或数字开头，可包含短横线和下划线')
    return
  }
  saving.value = true
  saveError.value = ''
  try {
    const details = {
      name,
      description: form.description.trim(),
    }
    if (editingRow.value) {
      await api.updateTemplate(editingRow.value.id, details)
    }
    else {
      const { data } = await api.createTemplate({ code, ...details })
      versions.value = { template: data.template, initialVersion: data.version }
    }
    editing.value = false
    saved()
  }
  catch (error) {
    saveError.value = errorText(error, t('保存失败'))
  }
  finally {
    saving.value = false
  }
}

function toggle(row: TemplateSummary) {
  if (!row.enabled) {
    void updateEnabled(row)
    return
  }

  window.$dialog.warning({
    title: t('停用模板'),
    content: t('停用「{0}」后，用户将无法使用此模板生成新文档。', row.name),
    positiveText: t('停用'),
    negativeText: t('取消'),
    onPositiveClick: () => updateEnabled(row),
  })
}

async function updateEnabled(row: TemplateSummary) {
  if (toggling.value)
    return
  toggling.value = row.id
  try {
    await api.updateTemplate(row.id, { enabled: !row.enabled })
    saved()
  }
  catch (error) {
    window.$message.error(errorText(error, t('操作失败')))
  }
  finally {
    toggling.value = null
  }
}

onMounted(() => table.value?.handleSearch())
</script>

<style scoped>
.docgen-admin {
  height: min(560px, 70vh);
}

.docgen-message {
  white-space: pre-wrap;
}
</style>
