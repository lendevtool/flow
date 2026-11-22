<template>
  <n-drawer :show="true" width="min(1100px, 100vw)" :aria-label="t(selectedId ? '流程模板详情' : '历史版本')" @update:show="!$event && emit('close')">
    <n-drawer-content :title="t(selectedId ? '流程模板详情' : '历史版本')" closable>
      <n-space vertical :size="14">
        <n-text v-if="!selectedId" depth="3">
          {{ template.name }} · {{ template.code }}
        </n-text>
        <NButton v-if="selectedId && historyMode" text type="primary" @click="backToHistory">
          {{ t('返回历史版本') }}
        </NButton>
        <template v-if="!selectedId">
          <n-data-table :columns="historyColumns" :data="history" :loading="historyLoading" :scroll-x="615" size="small">
            <template #empty>
              <n-empty :description="t('暂无历史版本')" />
            </template>
          </n-data-table>
          <n-pagination :page="historyPage" :item-count="historyTotal" :page-size="20" @update:page="loadHistory" />
        </template>
        <n-spin v-else :show="detailLoading">
          <n-tabs v-model:value="tab" type="line" @update:value="showTab">
            <n-tab-pane name="detail" :tab="t('详情')">
              <n-space v-if="detail" vertical :size="16">
                <n-descriptions bordered size="small" :column="2" label-placement="left">
                  <n-descriptions-item :label="t('名称')">
                    {{ detail.name }}
                  </n-descriptions-item>
                  <n-descriptions-item :label="t('编码')">
                    {{ detail.code }}
                  </n-descriptions-item>
                  <n-descriptions-item :label="t('模板版本')">
                    {{ detail.revision }}
                  </n-descriptions-item>
                  <n-descriptions-item :label="t('状态')">
                    {{ statusLabel(detail.status) }}
                  </n-descriptions-item>
                  <n-descriptions-item :label="t('发布时间')">
                    {{ publicationTime(detail.publishedAt) }}
                  </n-descriptions-item>
                </n-descriptions>
                <n-card size="small" :title="t('流程字段')">
                  <n-data-table :columns="fieldColumns" :data="fieldRows" :scroll-x="520" size="small" />
                </n-card>
                <n-card size="small" :title="t('流程节点')">
                  <n-data-table :columns="nodeColumns" :data="nodeRows" :scroll-x="1320" size="small" />
                </n-card>
              </n-space>
              <n-empty v-else-if="!detailLoading" :description="t('加载失败，请重试')" />
            </n-tab-pane>
            <n-tab-pane name="diagram" :tab="t('流程图')">
              <WorkflowDiagram v-if="detail" :definition="detail" />
              <n-empty v-else-if="!detailLoading" :description="t('加载失败，请重试')" />
            </n-tab-pane>
            <n-tab-pane name="instances" :tab="t('流程')">
              <n-data-table :columns="instanceColumns" :data="instances" :loading="instanceLoading" :scroll-x="1120" size="small">
                <template #empty>
                  <n-empty :description="t('暂无流程')" />
                </template>
              </n-data-table>
              <n-pagination class="mt-12" :page="instancePage" :item-count="instanceTotal" :page-size="20" @update:page="loadInstances" />
            </n-tab-pane>
          </n-tabs>
        </n-spin>
      </n-space>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { WorkflowDefinition, WorkflowNode } from './api'
import type { Instance } from '@/views/workbench/workflow/api'
import { NButton, NPopconfirm, NTag } from 'naive-ui'
import { computed, h, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import WorkflowDiagram from '@/components/workflow/WorkflowDiagram.vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import { workflowDefinitions } from './api'
import { dateTime, fieldTypeLabel, handlingModeLabels, handlingRoles, isCountersign, publicationTime, statusLabel } from './presentation'

type FieldRow = WorkflowDefinition['fields'][number] & { order: number }
type NodeRow = WorkflowNode & { label: string }
const props = defineProps<{ template: WorkflowDefinition, historyMode: boolean }>()
const emit = defineEmits<{ close: [] }>()
const router = useRouter()
const selectedId = ref<number>()
const tab = ref<'detail' | 'diagram' | 'instances'>('detail')
const detail = ref<WorkflowDefinition>()
const detailLoading = ref(false)
const history = ref<WorkflowDefinition[]>([])
const historyPage = ref(1)
const historyTotal = ref(0)
const historyLoading = ref(false)
const retiring = ref(false)
const instances = ref<Instance[]>([])
const instancePage = ref(1)
const instanceTotal = ref(0)
const instanceLoading = ref(false)
let detailRequest = 0
let historyRequest = 0
let instanceRequest = 0
let loadedInstanceId: number | undefined

const fieldRows = computed(() => (detail.value?.fields ?? []).map((field, index) => ({ ...field, order: index + 1 })))
const fieldNames = computed(() => new Map(fieldRows.value.map(field => [
  field.code,
  field.config
    ? displayName(field.config.name, field.config.nameEn)
    : field.code,
])))
const nodeRows = computed(() => {
  const nodes = detail.value?.nodes ?? []
  return nodes.filter(node => !node.parentKey).sort((a, b) => a.order - b.order).flatMap(root => [
    { ...root, label: `${root.order} · ${root.title}` },
    ...nodes.filter(node => node.parentKey === root.key).sort((a, b) => a.order - b.order).map(node => ({
      ...node,
      label: `${root.order}.${node.order} · ${node.title}`,
    })),
  ])
})
const flowStatuses = { RUNNING: '流程进行中', DONE: '已完成', CANCELLED: '已取消' }

function fieldList(codes: string[]) {
  return codes.map(code => fieldNames.value.get(code) ?? code).join('、') || '—'
}

const fieldColumns = computed<DataTableColumns<FieldRow>>(() => [
  {
    title: t('显示顺序'),
    key: 'order',
    width: 100,
  },
  {
    title: t('字段名称'),
    key: 'name',
    minWidth: 130,
    ellipsis: { tooltip: true },
    render: row => fieldNames.value.get(row.code),
  },
  {
    title: t('编码'),
    key: 'code',
    minWidth: 155,
    ellipsis: { tooltip: true },
  },
  {
    title: t('字段类型'),
    key: 'type',
    width: 135,
    render: row => fieldTypeLabel(row.config?.dataType),
  },
])

const nodeColumns = computed<DataTableColumns<NodeRow>>(() => [
  {
    title: t('节点名称'),
    key: 'label',
    minWidth: 160,
    ellipsis: { tooltip: true },
  },
  {
    title: t('处理角色'),
    key: 'roleCode',
    width: 165,
    ellipsis: { tooltip: true },
    render: row => handlingRoles(row).join('、') || '—',
  },
  {
    title: t('处理方式'),
    key: 'mode',
    width: 125,
    render: row => t(row.mode ? handlingModeLabels[row.mode] : '自动汇总'),
  },
  {
    title: t('可编辑'),
    key: 'editableFields',
    width: 185,
    ellipsis: { tooltip: true },
    render: row => fieldList(row.editableFields ?? (!row.parentKey && row.order === 1 && row.mode && !isCountersign(row.mode)
      ? fieldRows.value.map(field => field.code)
      : [])),
  },
  {
    title: t('提交时必填'),
    key: 'requiredFields',
    width: 180,
    ellipsis: { tooltip: true },
    render: row => fieldList(row.requiredFields ?? []),
  },
  {
    title: t('截止天数'),
    key: 'dueOffsetDays',
    width: 110,
    render: row => row.dueOffsetDays ?? '—',
  },
  {
    title: t('通过后通知场景'),
    key: 'onApprovedSceneCode',
    width: 195,
    ellipsis: { tooltip: true },
    render: row => row.onApprovedSceneCode ?? '—',
  },
  {
    title: t('驳回后通知场景'),
    key: 'onRejectedSceneCode',
    width: 195,
    ellipsis: { tooltip: true },
    render: row => row.onRejectedSceneCode ?? '—',
  },
])

const historyColumns = computed<DataTableColumns<WorkflowDefinition>>(() => [
  {
    title: t('模板版本'),
    key: 'revision',
    width: 105,
  },
  {
    title: t('名称'),
    key: 'name',
    minWidth: 160,
    ellipsis: { tooltip: true },
  },
  {
    title: t('状态'),
    key: 'status',
    width: 105,
    render: row => statusLabel(row.status),
  },
  {
    title: t('发布时间'),
    key: 'publishedAt',
    width: 165,
    render: row => publicationTime(row.publishedAt),
  },
  {
    title: t('操作'),
    key: 'actions',
    width: 125,
    render: row => h('div', { class: 'flex gap-12' }, [
      h(NButton, {
        text: true,
        type: 'primary',
        onClick: () => openVersion(row.id),
      }, () => t('详情')),
      row.status === 'PUBLISHED'
        ? h(NPopconfirm, {
            positiveText: t('停用'),
            negativeText: t('取消'),
            onPositiveClick: () => retire(row),
          }, {
            trigger: () => h(NButton, { text: true, type: 'warning', disabled: retiring.value }, () => t('停用')),
            default: () => t('确认停用该模板版本？'),
          })
        : null,
    ]),
  },
])

const instanceColumns = computed<DataTableColumns<Instance>>(() => [
  {
    title: t('流程编号'),
    key: 'id',
    width: 95,
    render: row => `WF-${row.id}`,
  },
  {
    title: t('流程标题'),
    key: 'title',
    minWidth: 180,
    ellipsis: { tooltip: true },
  },
  {
    title: t('发起人'),
    key: 'ownerName',
    width: 100,
    ellipsis: { tooltip: true },
    render: row => row.ownerName || '—',
  },
  {
    title: t('新建时间'),
    key: 'startedAt',
    width: 165,
    render: row => dateTime(row.startedAt),
  },
  {
    title: t('当前节点'),
    key: 'currentNodes',
    width: 150,
    ellipsis: { tooltip: true },
    render: row => row.currentNodes.map(node => node.title).join('、') || '—',
  },
  {
    title: t('当前处理人'),
    key: 'assignees',
    width: 190,
    ellipsis: { tooltip: true },
    render: row => row.currentNodes.map(node => node.status === 'WAITING_ASSIGNMENT'
      ? [
          t('待分派'),
          node.roleName,
        ].filter(Boolean).join(' · ')
      : node.assigneeNames.join('、') || '—').join(' / ') || '—',
  },
  {
    title: t('状态'),
    key: 'status',
    width: 125,
    render: row => h(NTag, {
      size: 'small',
      type: row.status === 'DONE'
        ? 'success'
        : 'default',
    }, () => t(flowStatuses[row.status])),
  },
  {
    title: t('操作'),
    key: 'actions',
    width: 95,
    render: row => h(NButton, {
      text: true,
      type: 'primary',
      onClick: () => openInstance(row.id),
    }, () => t('查看详情')),
  },
])

async function loadHistory(page: number) {
  const current = ++historyRequest
  historyLoading.value = true
  try {
    const { data } = await workflowDefinitions.history(props.template.id, page)
    if (current === historyRequest) {
      history.value = data.pageData
      historyTotal.value = data.total
      historyPage.value = page
    }
  }
  catch {
    // 请求错误由统一拦截器显示。
  }
  finally {
    if (current === historyRequest)
      historyLoading.value = false
  }
}

async function openVersion(id: number) {
  const current = ++detailRequest
  selectedId.value = id
  detail.value = undefined
  detailLoading.value = true
  tab.value = 'detail'
  try {
    const { data } = await workflowDefinitions.detail(id)
    if (current !== detailRequest)
      return
    detail.value = data
    if (data.status === 'DRAFT' && data.fields.length) {
      const fields = await workflowDefinitions.fieldsByCode(data.fields.map(field => field.code))
      if (current === detailRequest) {
        const configs = new Map(fields.data.map(({ config }) => [config.code, config]))
        detail.value = {
          ...data,
          fields: data.fields.map(field => ({
            ...field,
            config: configs.get(field.code),
          })),
        }
      }
    }
  }
  catch {
    // 请求错误由统一拦截器显示。
  }
  finally {
    if (current === detailRequest)
      detailLoading.value = false
  }
}

async function retire(row: WorkflowDefinition) {
  if (retiring.value)
    return
  retiring.value = true
  try {
    await workflowDefinitions.transition(row.id, 'retire', row.version)
    await loadHistory(historyPage.value)
  }
  catch {
    // 请求错误由统一拦截器显示。
  }
  finally {
    retiring.value = false
  }
}

function backToHistory() {
  detailRequest++
  instanceRequest++
  selectedId.value = undefined
  instances.value = []
  instancePage.value = 1
  instanceTotal.value = 0
  loadedInstanceId = undefined
}

async function loadInstances(page: number) {
  const id = selectedId.value
  if (!id)
    return
  const current = ++instanceRequest
  instanceLoading.value = true
  try {
    const { data } = await workflowDefinitions.instances(id, page)
    if (current === instanceRequest) {
      instances.value = data.pageData
      instancePage.value = page
      instanceTotal.value = data.total
      loadedInstanceId = id
    }
  }
  catch {
    // 请求错误由统一拦截器显示。
  }
  finally {
    if (current === instanceRequest)
      instanceLoading.value = false
  }
}

function showTab(value: string) {
  if (value === 'instances' && loadedInstanceId !== selectedId.value)
    void loadInstances(1)
}

function openInstance(id: number) {
  emit('close')
  void router.push({
    name: 'WorkflowTask',
    params: { id },
  })
}

onMounted(() => props.historyMode
  ? loadHistory(1)
  : openVersion(props.template.id))
</script>
