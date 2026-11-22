<template>
  <CommonPage>
    <template #action>
      <n-button type="primary" :disabled="busy" @click="newDraft()">
        <i class="i-material-symbols:add mr-4 text-18" />
        {{ t('新增流程模板') }}
      </n-button>
    </template>

    <div class="h-full flex flex-col">
      <n-alert v-if="listFailed" type="error" class="mb-12 shrink-0">
        {{ t('加载失败') }}
        <n-button text @click="table?.handleSearch(true)">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <MeCrud
        ref="table"
        v-model:query-items="queryItems"
        :scroll-x="1100"
        :page-size="20"
        :columns="columns"
        :get-data="workflowDefinitions.list"
        class="min-h-0 flex-1"
        @update:query-failed="listFailed = $event"
      >
        <MeQueryItem :label="t('模板编码或名称')" :label-width="90" :content-width="220">
          <n-input v-model:value="queryItems.keyword" clearable :maxlength="100" :placeholder="t('按名称或编码搜索')" />
        </MeQueryItem>
        <MeQueryItem :label="t('状态')" :label-width="50" :content-width="120">
          <n-select v-model:value="queryItems.status" :options="statusOptions" clearable />
        </MeQueryItem>
      </MeCrud>
    </div>

    <n-modal
      :show="!!draft"
      preset="card"
      :title="t(draftTitle)"
      :aria-label="t(draftTitle)"
      style="width: min(1060px, calc(100vw - 32px))"
      :content-style="{ maxHeight: 'min(65dvh, 680px)', overflow: 'auto' }"
      :closable="!busy"
      :mask-closable="!busy"
      :close-on-esc="!busy && !editor && !selectingFields"
      @update:show="!$event && closeDraft()"
    >
      <n-form v-if="draft" id="workflow-draft" @submit.prevent="saveDraft">
        <n-grid cols="1 520:2" responsive="screen" :x-gap="16">
          <n-form-item-gi :label="t('编码')" required>
            <n-input v-model:value="draft.code" :disabled="Boolean(draftId) || cloning" :maxlength="64" />
          </n-form-item-gi>
          <n-form-item-gi :label="t('名称')" required>
            <n-input v-model:value="draft.name" :maxlength="200" />
          </n-form-item-gi>
        </n-grid>
        <n-space vertical :size="16">
          <n-card size="small" :title="t('流程字段')">
            <template #header-extra>
              <n-button size="small" @click="openFields">
                {{ t('从字段管理选择') }}
              </n-button>
            </template>
            <n-table v-if="draft.fields.length" :aria-label="t('流程字段')" size="small" :bordered="false">
              <thead>
                <tr>
                  <th>{{ t('显示顺序') }}</th>
                  <th>{{ t('字段名称') }}</th>
                  <th>{{ t('编码') }}</th>
                  <th>{{ t('字段类型') }}</th>
                  <th>{{ t('操作') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(code, index) in draft.fields" :key="code">
                  <td>{{ index + 1 }}</td>
                  <td>{{ fieldLabels.get(code) || code }}</td>
                  <td>{{ code }}</td>
                  <td>{{ fieldTypeLabel(fieldConfigs.get(code)?.dataType) }}</td>
                  <td>
                    <n-space>
                      <n-button text :disabled="index === 0" @click="moveField(index, -1)">
                        {{ t('上移') }}
                      </n-button>
                      <n-button text :disabled="index === draft.fields.length - 1" @click="moveField(index, 1)">
                        {{ t('下移') }}
                      </n-button>
                      <n-button text type="error" @click="removeField(code)">
                        {{ t('移除') }}
                      </n-button>
                    </n-space>
                  </td>
                </tr>
              </tbody>
            </n-table>
            <n-empty v-else :description="t('暂无流程字段')" />
          </n-card>
          <n-card size="small" :title="t('流程节点')">
            <template #header-extra>
              <n-button size="small" @click="openNode()">
                {{ t('新增一级节点') }}
              </n-button>
            </template>
            <n-table size="small" :bordered="false">
              <thead>
                <tr>
                  <th>{{ t('节点名称') }}</th>
                  <th>{{ t('处理角色') }}</th>
                  <th>{{ t('操作') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="node in orderedNodes" :key="node.key">
                  <td>
                    <span :class="{ 'pl-20': node.parentKey }">
                      {{ nodeLabel(node) }}
                    </span>
                  </td>
                  <td>{{ handlingRoles(node).map(code => roleLabels.get(code) ?? code).join('、') || t('自动汇总') }}</td>
                  <td>
                    <n-space>
                      <n-button text type="primary" @click="openNode(node)">
                        {{ t('编辑节点') }}
                      </n-button>
                      <n-button v-if="!node.parentKey" text @click="openNode(undefined, node)">
                        {{ t('新增子节点') }}
                      </n-button>
                      <n-button text :disabled="node.order === 1" @click="moveNode(node, -1)">
                        {{ t('上移') }}
                      </n-button>
                      <n-button text :disabled="node.order === siblings(node.parentKey).length" @click="moveNode(node, 1)">
                        {{ t('下移') }}
                      </n-button>
                      <n-popconfirm @positive-click="removeNode(node)">
                        <template #trigger>
                          <n-button text type="error">
                            {{ t('删除') }}
                          </n-button>
                        </template>
                        {{ t('确认移除此节点及其子节点？') }}
                      </n-popconfirm>
                    </n-space>
                  </td>
                </tr>
              </tbody>
            </n-table>
          </n-card>
        </n-space>
      </n-form>
      <template #footer>
        <n-space>
          <n-button :disabled="busy" @click="closeDraft">
            {{ t('取消') }}
          </n-button>
          <n-button type="primary" attr-type="submit" form="workflow-draft" :loading="busy" :disabled="!canSave">
            {{ t('保存草稿') }}
          </n-button>
        </n-space>
      </template>
    </n-modal>

    <WorkflowNodeEditor
      v-if="editor && draft"
      :node="editor.node"
      :title="editor.title"
      :summary="editor.summary"
      :parent-title="editor.parent?.title"
      :converting-parent="!editor.existing && !!editor.parent?.mode"
      :fields="draft.fields"
      :labels="fieldLabels"
      :roles="roleOptions"
      :roles-loading="rolesLoading"
      :scenes="notificationScenes"
      :scenes-loading="scenesLoading"
      :scenes-failed="scenesFailed"
      @close="editor = null"
      @reload-scenes="loadNotificationScenes"
      @save="saveNode"
    />

    <WorkflowFieldPicker
      v-if="selectingFields && draft"
      :selected="draft.fields"
      @close="selectingFields = false"
      @confirm="applyFields"
    />
    <WorkflowTemplateDrawer v-if="inspecting" :template="inspecting.row" :history-mode="inspecting.history" @close="inspecting = null" />
  </CommonPage>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { WorkflowDefinition, WorkflowDraft, WorkflowNode, WorkflowNotificationScene, WorkflowRole, WorkflowStatus, WorkflowTemplateFilters } from './api'
import type { FieldDefinition } from '@/views/pms/field/api'
import { NTag } from 'naive-ui'
import { computed, h, onMounted, ref } from 'vue'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import MeQueryItem from '@/components/me/crud/QueryItem.vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import { workflowDefinitions } from './api'
import { fieldTypeLabel, fromWorkflowDefinition, handlingRoles, publicationTime, statusLabel } from './presentation'
import WorkflowFieldPicker from './WorkflowFieldPicker.vue'
import WorkflowNodeEditor from './WorkflowNodeEditor.vue'
import WorkflowTemplateDrawer from './WorkflowTemplateDrawer.vue'

defineOptions({ name: 'WorkflowTemplateMgt' })

const table = ref<InstanceType<typeof MeCrud> | null>(null)
const queryItems = ref<WorkflowTemplateFilters>({ keyword: null, status: null })
const listFailed = ref(false)
const busy = ref(false)
const statuses: WorkflowStatus[] = ['DRAFT', 'PUBLISHED', 'RETIRED']
const statusOptions = computed(() => statuses.map(value => ({
  value,
  label: statusLabel(value),
})))
const roles = ref<WorkflowRole[]>([])
const rolesLoading = ref(false)
const notificationScenes = ref<WorkflowNotificationScene[]>([])
const scenesLoading = ref(false)
const scenesFailed = ref(false)
const roleLabels = computed(() => new Map(roles.value.map(role => [
  role.code,
  displayName(role.name, role.nameEn),
])))
const roleOptions = computed(() => roles.value.map(role => ({
  value: role.code,
  label: displayName(role.name, role.nameEn),
  hasEnabledMembers: role.hasEnabledMembers,
})))
const draftId = ref<number>()
const cloning = ref(false)
const draft = ref<WorkflowDraft>()
const draftTitle = computed(() => {
  if (draftId.value)
    return '编辑流程模板'
  return cloning.value ? '新建版本' : '新增流程模板'
})
const canSave = computed(() => !!draft.value && /^[A-Z0-9][\w.:-]*$/i.test(draft.value.code.trim()) && !!draft.value.name.trim() && !!draft.value.nodes.length)
const orderedNodes = computed(() => siblings(null).flatMap(node => [
  node,
  ...siblings(node.key),
]))
const editor = ref<{ title: string, node: WorkflowNode, parent?: WorkflowNode, existing: boolean, summary: boolean } | null>(null)
const inspecting = ref<{ row: WorkflowDefinition, history: boolean } | null>(null)
const selectingFields = ref(false)
const fieldConfigs = ref(new Map<string, FieldDefinition['config']>())
const fieldLabels = computed(() => new Map([...fieldConfigs.value].map(([code, config]) => [
  code,
  displayName(config.name, config.nameEn),
])))
let draftRequest = 0
let roleRequest = 0

function siblings(parent: string | null) {
  return (draft.value?.nodes ?? []).filter(node => node.parentKey === parent).sort((a, b) => a.order - b.order)
}

function nodeLabel(node: WorkflowNode) {
  const parent = draft.value?.nodes.find(item => item.key === node.parentKey)
  return `${parent
    ? `${parent.order}.`
    : ''}${node.order} · ${node.title}`
}

const columns = computed<DataTableColumns<WorkflowDefinition>>(() => [
  {
    title: t('名称'),
    key: 'name',
    minWidth: 150,
    ellipsis: { tooltip: true },
  },
  {
    title: t('编码'),
    key: 'code',
    minWidth: 150,
    ellipsis: { tooltip: true },
  },
  {
    title: t('模板版本'),
    key: 'revision',
    width: 90,
  },
  {
    title: t('状态'),
    key: 'status',
    width: 90,
    render: row => h(NTag, {
      size: 'small',
      type: row.status === 'PUBLISHED'
        ? 'success'
        : 'default',
    }, () => statusLabel(row.status)),
  },
  {
    title: t('发布时间'),
    key: 'publishedAt',
    width: 164,
    ellipsis: { tooltip: true },
    render: row => publicationTime(row.publishedAt),
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

function actionsFor(row: WorkflowDefinition) {
  const isDraft = row.status === 'DRAFT'
  const actions: InstanceType<typeof TableActions>['$props']['actions'][number][] = [
    {
      key: 'detail',
      label: t('详情'),
      icon: 'i-fe:eye text-14',
      secondary: true,
      onClick: () => inspect(row, false),
    },
    {
      key: 'history',
      label: t('历史版本'),
      icon: 'i-fe:clock text-14',
      secondary: true,
      onClick: () => inspect(row, true),
    },
    {
      key: isDraft ? 'edit' : 'new-version',
      label: t(isDraft ? '编辑' : '新建版本'),
      icon: isDraft ? undefined : 'i-material-symbols:add text-14',
      type: isDraft ? 'default' : 'primary',
      secondary: !isDraft,
      disabled: busy.value,
      onClick: () => openExistingDraft(row, !isDraft),
    },
  ]
  if (row.status !== 'RETIRED') {
    actions.push({
      key: isDraft ? 'publish' : 'retire',
      label: t(isDraft ? '发布' : '停用'),
      icon: isDraft ? 'i-fe:send text-14' : 'i-fe:slash text-14',
      type: isDraft ? 'primary' : 'error',
      disabled: busy.value,
      onClick: () => confirmTransition(row),
    })
  }
  return actions
}

async function loadRoles() {
  const current = ++roleRequest
  rolesLoading.value = true
  try {
    const { data } = await workflowDefinitions.roles()
    if (current === roleRequest)
      roles.value = data
  }
  catch {
    if (current === roleRequest)
      roles.value = []
  }
  finally {
    if (current === roleRequest)
      rolesLoading.value = false
  }
}

async function loadNotificationScenes() {
  if (scenesLoading.value)
    return
  scenesLoading.value = true
  scenesFailed.value = false
  try {
    notificationScenes.value = (await workflowDefinitions.notificationScenes()).data
  }
  catch {
    notificationScenes.value = []
    scenesFailed.value = true
  }
  finally {
    scenesLoading.value = false
  }
}

function newDraft(row?: WorkflowDefinition, clone = false) {
  draftId.value = clone ? undefined : row?.id
  cloning.value = clone
  fieldConfigs.value = new Map()
  draft.value = row
    ? fromWorkflowDefinition(row, clone)
    : {
        code: '',
        name: '',
        fields: [],
        nodes: [],
      }
  void loadRoles()
  void loadFieldDetails(row, ++draftRequest)
}

function inspect(row: WorkflowDefinition, history: boolean) {
  inspecting.value = { row, history }
}

async function openExistingDraft(row: WorkflowDefinition, clone: boolean) {
  if (busy.value)
    return
  busy.value = true
  try {
    // 详情按模板版本返回节点场景，复制时保留来源版本中的关联。
    const { data } = await workflowDefinitions.detail(row.id)
    newDraft(data, clone)
  }
  catch {
    // HTTP 拦截器展示请求错误。
  }
  finally {
    busy.value = false
  }
}

function closeDraft() {
  if (!busy.value && !editor.value && !selectingFields.value) {
    draftRequest++
    draft.value = undefined
  }
}

async function saveDraft() {
  if (!draft.value || !canSave.value || busy.value)
    return
  busy.value = true
  const payload = {
    ...draft.value,
    code: draft.value.code.trim(),
    name: draft.value.name.trim(),
  }
  try {
    if (draftId.value)
      await workflowDefinitions.edit(draftId.value, payload)
    else
      await workflowDefinitions.create(payload)
    draft.value = undefined
    window.$message.success(t('草稿已保存'))
    await table.value?.handleSearch(Boolean(draftId.value))
  }
  catch {
    // HTTP 拦截器展示请求错误。
  }
  finally {
    busy.value = false
  }
}

function notificationWarningText(definition: WorkflowDefinition) {
  const names = new Map(definition.nodes.map(node => [node.key, node.title]))
  return (definition.notificationWarnings ?? []).filter(warning => warning.reason === 'NO_GROUPS').map(warning => t(
    '节点「{0}」的{1}「{2}」没有绑定通知群组。',
    names.get(warning.nodeKey) ?? warning.nodeKey,
    t(warning.triggerType === 'APPROVED'
      ? '通过后通知场景'
      : '驳回后通知场景'),
    warning.sceneCode,
  )).join('\n')
}

async function confirmTransition(row: WorkflowDefinition) {
  if (busy.value)
    return
  const publishing = row.status === 'DRAFT'
  const label = t(publishing ? '发布' : '停用')
  let warning = ''
  if (publishing) {
    busy.value = true
    try {
      const { data } = await workflowDefinitions.detail(row.id)
      warning = notificationWarningText(data)
    }
    catch {
      return
    }
    finally {
      busy.value = false
    }
  }
  const confirmation = t(publishing ? '确认发布该模板版本？' : '确认停用该模板版本？')
  window.$dialog.warning({
    title: label,
    content: () => h('div', { class: 'whitespace-pre-line' }, warning
      ? `${warning}\n\n${confirmation}`
      : confirmation),
    positiveText: label,
    negativeText: t('取消'),
    async onPositiveClick() {
      if (busy.value)
        return false
      busy.value = true
      try {
        const { data } = await workflowDefinitions.transition(row.id, publishing ? 'publish' : 'retire', row.version)
        const latestWarning = publishing
          ? notificationWarningText(data)
          : ''
        if (latestWarning && latestWarning !== warning)
          window.$message.warning(latestWarning)
        await table.value?.handleSearch(true)
      }
      catch {
        return false
      }
      finally {
        busy.value = false
      }
    },
  })
}

function stableNodeKey() {
  let counter = 1
  while (draft.value?.nodes.some(node => node.key === `node_${counter}`))
    counter++
  return `node_${counter}`
}

function openNode(node?: WorkflowNode, parent?: WorkflowNode) {
  void loadRoles()
  void loadNotificationScenes()
  const parentNode = parent ?? draft.value?.nodes.find(item => item.key === node?.parentKey)
  const order = siblings(parentNode?.key ?? null).length + 1
  editor.value = {
    title: t(node
      ? (parentNode ? '编辑子节点' : '编辑一级节点')
      : (parentNode ? '新增子节点' : '新增一级节点')),
    node: node ?? {
      key: stableNodeKey(),
      parentKey: parentNode?.key ?? null,
      order,
      title: '',
      roleCode: null,
      mode: 'PRIMARY',
      editableFields: !parentNode && order === 1
        ? null
        : [],
      requiredFields: [],
      dueOffsetDays: null,
    },
    parent: parentNode,
    existing: Boolean(node),
    summary: Boolean(node && siblings(node.key).length),
  }
}

function saveNode(node: WorkflowNode) {
  if (!draft.value || !editor.value)
    return
  if (editor.value.existing) {
    draft.value.nodes = draft.value.nodes.map(item => item.key === node.key
      ? node
      : item)
  }
  else {
    const parent = editor.value.parent
    if (parent?.mode) {
      Object.assign(parent, {
        roleCode: null,
        roleCodes: null,
        mode: null,
        editableFields: [],
        requiredFields: [],
        dueOffsetDays: null,
        onRejectedSceneCode: null,
      })
    }
    draft.value.nodes.push(node)
  }
  editor.value = null
}

function moveNode(node: WorkflowNode, offset: number) {
  const other = siblings(node.parentKey).find(item => item.order === node.order + offset)
  if (!other)
    return
  other.order = node.order
  node.order += offset
}

function removeNode(node: WorkflowNode) {
  if (!draft.value)
    return
  draft.value.nodes = draft.value.nodes.filter(item => item.key !== node.key && item.parentKey !== node.key)
  const group = siblings(node.parentKey)
  group.forEach((item, index) => item.order = index + 1)
}

async function loadFieldDetails(row: WorkflowDefinition | undefined, current: number) {
  if (!row?.fields.length)
    return
  for (const field of row.fields) {
    if (field.config)
      fieldConfigs.value.set(field.code, field.config)
  }
  try {
    const { data } = await workflowDefinitions.fieldsByCode(row.fields.map(field => field.code))
    if (current !== draftRequest)
      return
    for (const { config } of data)
      fieldConfigs.value.set(config.code, config)
  }
  catch {
    // 请求错误由统一拦截器显示，列表仍可使用字段编码识别已有配置。
  }
}

function moveField(index: number, offset: number) {
  const fields = draft.value?.fields
  const target = index + offset
  if (!fields || target < 0 || target >= fields.length)
    return
  const item = fields[index]
  fields.splice(index, 1)
  fields.splice(target, 0, item)
}

function removeField(code: string) {
  if (!draft.value)
    return
  draft.value.fields = draft.value.fields.filter(field => field !== code)
  for (const node of draft.value.nodes) {
    node.editableFields = node.editableFields?.filter(field => field !== code) ?? null
    node.requiredFields = node.requiredFields.filter(field => field !== code)
  }
}

function applyFields(codes: string[], fields: FieldDefinition[]) {
  if (!draft.value)
    return
  for (const { config } of fields)
    fieldConfigs.value.set(config.code, config)
  for (const code of draft.value.fields.filter(code => !codes.includes(code)))
    removeField(code)
  draft.value.fields = [...codes]
  selectingFields.value = false
}

function openFields() {
  selectingFields.value = true
}

onMounted(() => table.value?.handleSearch())
</script>
