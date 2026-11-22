<template>
  <div class="min-h-0 flex flex-col flex-1">
    <n-alert v-if="listFailed" type="error" class="mb-12 shrink-0">
      {{ t('加载失败') }}
      <n-button text @click="reload">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <MeCrud
      ref="table"
      :page-size="PAGE_SIZE"
      :columns="columns"
      :get-data="api.scenes"
      :scroll-x="canBind && canEdit ? 1000 : 870"
      row-key="sceneCode"
      class="min-h-0 flex-1"
      @update:query-failed="listFailed = $event"
    >
      <template #empty="{ loading }">
        <n-empty v-if="!loading && !listFailed" :description="t('暂无已登记的通知场景')" />
      </template>
    </MeCrud>
    <SceneEditor
      v-if="editingCode"
      :key="editingCode"
      :scene-code="editingCode"
      :can-audit="canAudit"
      @close="editingCode = null"
      @saved="saved"
    />
    <WorkflowSceneEditor
      v-if="workflowEditor"
      :key="workflowEditor.sceneCode ?? 'new-workflow-scene'"
      :scene-code="workflowEditor.sceneCode"
      :can-audit="canAudit"
      @close="workflowEditor = null"
      @saved="saved"
    />
  </div>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { SceneRow } from './api'
import { NTag } from 'naive-ui'
import { computed, h, onMounted, ref } from 'vue'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import api from './api'
import { PAGE_SIZE, sceneTypeLabel } from './model'
import SceneEditor from './SceneEditor.vue'
import WorkflowSceneEditor from './WorkflowSceneEditor.vue'

const props = defineProps<{ canAudit: boolean, canBind: boolean, canEdit: boolean }>()
const emit = defineEmits<{ saved: [] }>()
defineExpose({ openCreate })
const table = ref<InstanceType<typeof MeCrud> | null>(null)
const listFailed = ref(false)
const editingCode = ref<string | null>(null)
const workflowEditor = ref<{ sceneCode?: string } | null>(null)

const columns = computed<DataTableColumns<SceneRow>>(() => [
  {
    title: t('场景名称'),
    key: 'name',
    minWidth: 170,
    render: row => displayName(row.nameCn, row.nameEn),
    ellipsis: { tooltip: true },
  },
  {
    title: t('场景编码'),
    key: 'sceneCode',
    minWidth: 155,
    ellipsis: { tooltip: true },
  },
  {
    title: t('场景类型'),
    key: 'sceneType',
    width: 145,
    render: row => sceneTypeLabel(row.sceneType),
  },
  {
    title: t('状态'),
    key: 'enabled',
    width: 92,
    render: row => h(NTag, {
      size: 'small',
      type: row.enabled === false
        ? 'default'
        : 'success',
    }, () => t(row.enabled === false
      ? '停用'
      : '启用')),
  },
  {
    title: t('群组数'),
    key: 'groupCount',
    width: 100,
    align: 'right',
    render: row => row.sceneType === 'WORKFLOW_SYSTEM'
      ? '—'
      : row.groupCount,
  },
  {
    title: t('补充邮件'),
    key: 'mailTemplateId',
    width: 130,
    render: row => !row.sceneType || row.sceneType === 'BUSINESS'
      ? '—'
      : t(row.mailTemplateId == null
          ? '关闭'
          : '已配置'),
  },
  {
    title: t('操作'),
    key: 'actions',
    width: props.canBind && props.canEdit
      ? 210
      : 145,
    fixed: 'right',
    align: 'right',
    render: row => h(TableActions, {
      actions: actionsFor(row),
    }),
  },
])

function actionsFor(row: SceneRow) {
  const actions: InstanceType<typeof TableActions>['$props']['actions'][number][] = []
  if (props.canEdit && (row.sceneType === 'WORKFLOW_SYSTEM' || row.sceneType === 'WORKFLOW_EXTRA')) {
    actions.push({
      key: 'config',
      label: t(row.sceneType === 'WORKFLOW_SYSTEM'
        ? '配置邮件'
        : '编辑场景'),
      icon: 'i-fe:settings',
      onClick: () => workflowEditor.value = { sceneCode: row.sceneCode },
    })
  }
  if (props.canBind && row.sceneType !== 'WORKFLOW_SYSTEM') {
    actions.push({
      key: 'edit',
      label: t('编辑绑定'),
      icon: 'i-fe:edit-2',
      onClick: () => editingCode.value = row.sceneCode,
    })
  }
  return actions
}

function openCreate() {
  if (props.canEdit)
    workflowEditor.value = {}
}

function reload() {
  void table.value?.handleSearch(true)
}

function saved() {
  const keepPage = workflowEditor.value === null || workflowEditor.value.sceneCode != null
  editingCode.value = null
  workflowEditor.value = null
  window.$message.success(t('操作成功'))
  void table.value?.handleSearch(keepPage)
  emit('saved')
}

onMounted(reload)
</script>
