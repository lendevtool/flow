<template>
  <n-drawer
    :show="true"
    placement="left"
    width="88%"
    :aria-label="drawerTitle"
    :mask-closable="canClose"
    :close-on-esc="canClose"
    @update:show="!$event && canClose && emit('close')"
  >
    <n-drawer-content :title="drawerTitle" :closable="canClose">
      <div v-if="definition" class="workflow-page">
        <div class="workflow-heading">
          <h1>{{ detail?.instance.title ?? definition.name }}</h1>
          <n-dropdown v-if="actions.length" trigger="click" :options="actions" @select="perform">
            <n-button secondary type="primary" :disabled="working || !!uploading">
              {{ status }} <i class="i-fe:chevron-down ml-4" aria-hidden="true" />
            </n-button>
          </n-dropdown>
          <n-tag v-else :type="detail?.instance.status === 'DONE' ? 'success' : 'default'">
            {{ status }}
          </n-tag>
        </div>
        <div v-if="detail" class="workflow-meta">
          <n-text depth="3">
            WF-{{ detail.instance.id }} · {{ t('发起人') }} {{ detail.instance.ownerName || '—' }} · {{ t('发起时间') }} {{ formatTime(detail.instance.startedAt) }}
          </n-text>
          <div v-if="active" class="workflow-current">
            <strong>{{ t('当前节点') }}</strong>
            <n-select
              v-if="available.length > 1"
              v-model:value="todoId"
              :aria-label="t('当前节点')"
              :disabled="!!uploading || working"
              :options="taskOptions"
              class="workflow-task-select"
              size="small"
            />
            <span v-else>{{ names[active.nodeKey] }}</span>
            <n-tag size="small" type="success">
              {{ t(identities[active.identity]) }}
            </n-tag>
          </div>
        </div>
        <n-tabs type="line" default-value="flow">
          <n-tab-pane name="flow" :tab="t('流程')" display-directive="show">
            <div class="workflow-page">
              <n-alert v-if="waitingRoles.length" type="warning" :title="t('待分派')">
                {{ waitingRoles.join(' · ') }}
              </n-alert>
              <RouterLink v-if="definition.code === 'INSTITUTION_CHANGE' && detail?.sourceUrl" :to="detail.sourceUrl">
                {{ t('查看送审原值') }}
              </RouterLink>

              <WorkflowFields
                :fields="definition.fields"
                :values="values"
                :saved="detail?.values ?? {}"
                :editable="active?.editableFields ?? []"
                :required="active?.requiredFields ?? []"
                :instance-id="detail?.instance.id"
                :attachment-names="attachmentNames"
                :uploading="uploading"
                :disabled="working"
                @change="change"
                @upload="upload"
                @download="download"
              />

              <n-card v-if="active || mode === 'start'" size="small">
                <div v-if="active && !initial" class="opinion-row">
                  <span>{{ t('审批意见') }}</span>
                  <WorkflowOpinion v-model="opinion" />
                </div>
                <n-space justify="end" :class="{ 'mt-10': active && !initial }">
                  <n-button v-if="active?.rejectTargets.length" type="error" secondary :disabled="working || !!uploading" @click="perform('reject')">
                    {{ t('驳回') }}
                  </n-button>
                  <n-button type="primary" :loading="working" :disabled="!!uploading" @click="perform(mode === 'start' ? 'start' : 'submit')">
                    {{ t(mode === 'start' ? '确认发起' : initial ? '提交到下一节点' : '通过并提交') }}
                  </n-button>
                </n-space>
              </n-card>

              <WorkflowHistory :records="records" :page="historyPage" :total="historyTotal" :names="names" @page="loadHistory" />
            </div>
          </n-tab-pane>
          <n-tab-pane name="info" :tab="t('流程信息')" display-directive="show:lazy">
            <div class="workflow-page">
              <n-card size="small" :title="t('其他流程信息')">
                {{ t('流程模板') }} · {{ definition.code }} / {{ definition.revision }}
                <RouterLink v-if="detail?.sourceUrl" class="ml-12" :to="detail.sourceUrl">
                  {{ t('打开来源') }}
                </RouterLink>
              </n-card>
              <WorkflowDiagram :definition="definition" :detail="detail" />
            </div>
          </n-tab-pane>
        </n-tabs>
        <n-modal v-if="pending" preset="card" :show="true" :title="t(labels[pending])" style="width: min(510px, calc(100vw - 32px))" @update:show="!$event && !working && (pending = null)">
          <n-form v-if="pending === 'reject' || pending === 'cancel'" @submit.prevent="confirm">
            <n-form-item v-if="pending === 'reject'" :label="t('目标节点')">
              <n-select v-model:value="target" :options="targets" />
            </n-form-item>
            <n-form-item :label="t('原因')" required>
              <n-input v-model:value="reason" type="textarea" :maxlength="500" />
            </n-form-item>
          </n-form>
          <p v-else>
            {{ t('收回后关闭下一节点待办，并重新开放原节点。') }}
          </p>
          <n-space justify="end">
            <n-button :disabled="working" @click="pending = null">
              {{ t('取消') }}
            </n-button>
            <n-button type="primary" :loading="working" :disabled="pending !== 'recall' && !reason.trim()" @click="confirm">
              {{ t('确认') }}
            </n-button>
          </n-space>
        </n-modal>
      </div>
      <n-space v-else-if="loading" aria-busy="true" aria-live="polite" align="center" justify="center" class="py-40">
        <n-spin size="small" />
        <n-text depth="3">
          {{ t('正在加载流程') }}
        </n-text>
      </n-space>
      <n-empty v-else class="py-40" :description="t('无法读取流程')">
        <template #extra>
          <n-button size="small" @click="emit('retry')">
            {{ t('重试') }}
          </n-button>
        </template>
      </n-empty>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { Action, AttachmentRef, Detail, History, WorkflowValue, WorkflowValues } from './api'
import type { WorkflowDefinition } from '@/views/pms/workflow/api'
import { computed, ref, watch } from 'vue'
import WorkflowDiagram from '@/components/workflow/WorkflowDiagram.vue'
import { t } from '@/i18n'
import api from './api'
import WorkflowFields from './WorkflowFields.vue'
import WorkflowHistory from './WorkflowHistory.vue'
import WorkflowOpinion from './WorkflowOpinion.vue'

const props = defineProps<{ mode: 'start' | 'detail' | 'task', definition?: WorkflowDefinition, detail?: Detail, focusTodo?: number, loading?: boolean }>()
const emit = defineEmits<{ changed: [Detail], started: [Detail], close: [], retry: [] }>()
type Pending = 'reject' | 'cancel' | 'recall'
const labels: Record<Action | 'start', string> = {
  'submit': '通过并提交',
  'reject': '驳回',
  'recall': '收回',
  'cancel': '取消流程',
  'retry-assignment': '重新分派',
  'start': '确认发起',
}
const identities = {
  PRIMARY: '主办',
  DELEGATE: '转办人',
  BROADCAST: '广播成员',
  COUNTERSIGN: '会签成员',
}
const states = { RUNNING: '流程进行中', DONE: '已完成', CANCELLED: '已取消' }
const patch = ref<WorkflowValues>({})
const localNames = ref<Record<string, string>>({})
const opinion = ref('')
const todoId = ref<number | null>(null)
const working = ref(false)
const uploading = ref('')
const pending = ref<Pending | null>(null)
const canClose = computed(() => !working.value && !uploading.value && !pending.value)
const drawerTitle = computed(() => t({ start: '发起流程', task: '流程处理', detail: '流程详情' }[props.mode]))
const reason = ref('')
const target = ref<string | null>(null)
const records = ref<History[]>([])
const historyPage = ref(1)
const historyTotal = ref(0)
let historyRequest = 0
let actionKey: { signature: string, key: string } | undefined

const definition = computed(() => props.detail?.definition ?? props.definition)
const nodes = computed(() => definition.value?.nodes ?? [])
const names = computed(() => Object.fromEntries(nodes.value.map(node => [node.key, node.title])))
const stages = computed(() => nodes.value.filter(node => !node.parentKey).sort((first, second) => first.order - second.order))
const waitingRoles = computed(() => [
  ...new Set(props.detail?.instance.currentNodes?.flatMap(node =>
    node.status === 'WAITING_ASSIGNMENT' && node.roleName
      ? [node.roleName]
      : []) ?? []),
])
const available = computed(() => props.detail?.tasks.filter(task => task.canSubmit) ?? [])
const taskOptions = computed(() => available.value.map(task => ({ value: task.todoId, label: names.value[task.nodeKey] })))
const active = computed(() => props.mode === 'task'
  ? available.value.find(task => task.todoId === todoId.value)
  : undefined)
const initial = computed(() => props.detail?.instance.currentStepNo === 1
  && nodes.value.find(node => node.key === active.value?.nodeKey)?.mode !== 'MULTI_ALL')
const values = computed(() => ({
  ...(props.detail?.values ?? {}),
  ...patch.value,
}))
const attachmentNames = computed(() => ({
  ...(props.detail?.attachmentNames ?? {}),
  ...localNames.value,
}))
const status = computed(() => t(props.detail ? states[props.detail.instance.status] : '待发起'))
const targets = computed(() => active.value?.rejectTargets.map(key => ({ value: key, label: names.value[key] || key })) ?? [])
const actions = computed(() => {
  const keys: (Action | 'start')[] = []
  if (props.mode === 'start')
    keys.push('start')
  if (active.value) {
    keys.push('submit')
    if (active.value.rejectTargets.length)
      keys.push('reject')
  }
  if (props.detail?.canRecall)
    keys.push('recall')
  if (props.detail?.canRetry)
    keys.push('retry-assignment')
  if (props.detail?.canCancel)
    keys.push('cancel')
  return keys.map(key => ({
    key,
    label: t(key === 'submit' && initial.value
      ? '提交到下一节点'
      : labels[key]),
  }))
})

watch([
  () => props.detail?.instance.id,
  () => props.detail?.instance.version,
  () => props.focusTodo,
  available,
], () => {
  todoId.value = available.value.find(task => task.todoId === props.focusTodo)?.todoId ?? available.value[0]?.todoId ?? null
  void loadHistory(1)
}, { immediate: true })
watch(todoId, () => {
  patch.value = {}
  localNames.value = {}
  opinion.value = ''
})

function formatTime(value: string) {
  return value.replace('T', ' ').slice(0, 16)
}
function change(code: string, value: WorkflowValue) {
  patch.value[code] = value
}

async function loadHistory(page: number) {
  const id = props.detail?.instance.id
  const current = ++historyRequest
  if (!id)
    return
  try {
    const { data } = await api.history(id, page)
    if (current !== historyRequest)
      return
    records.value = data.pageData
    historyTotal.value = data.total
    historyPage.value = page
  }
  catch {
    // 请求错误由统一拦截器显示。
  }
}

async function upload(code: string, files: File[]) {
  const id = props.detail?.instance.id
  const task = active.value
  if (!id || !task || uploading.value)
    return
  uploading.value = code
  try {
    for (const file of files) {
      try {
        const { data } = await api.upload(id, task.id, code, file)
        const current = values.value[code]
        change(code, [
          ...(Array.isArray(current)
            ? current.filter(item => typeof item === 'object')
            : []),
          { documentId: data.documentId, versionId: data.versionId },
        ])
        localNames.value[data.versionId] = data.name
      }
      catch {
        // 逐个上传，失败项可另行上传。
      }
    }
  }
  finally {
    uploading.value = ''
  }
}

async function download(file: AttachmentRef) {
  if (!props.detail)
    return
  try {
    const response = await api.download(props.detail.instance.id, file)
    const url = URL.createObjectURL(response.data)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = attachmentNames.value[file.versionId] ?? file.documentId
    anchor.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  catch {
    // 请求错误由统一拦截器显示。
  }
}

function requestKey(signature: string) {
  if (actionKey?.signature !== signature) {
    actionKey = {
      signature,
      key: Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join(''),
    }
  }
  return actionKey.key
}

async function execute(key: Action | 'start') {
  const currentDefinition = definition.value
  if (!currentDefinition || working.value || uploading.value)
    return
  working.value = true
  try {
    if (key === 'start') {
      const body = { definitionId: currentDefinition.id, title: currentDefinition.name }
      const { data } = await api.start({
        ...body,
        requestKey: requestKey(JSON.stringify(body)),
      })
      emit('started', data)
    }
    else if (props.detail) {
      const body = {
        version: props.detail.instance.version,
        todoId: active.value?.todoId,
        targetNodeKey: key === 'reject'
          ? target.value ?? undefined
          : undefined,
        reason: key === 'cancel' || key === 'reject'
          ? reason.value
          : undefined,
        opinion: key === 'submit' || key === 'reject'
          ? opinion.value
          : undefined,
        fields: key === 'submit'
          ? patch.value
          : undefined,
      }
      const { data } = await api.act(props.detail.instance.id, key, {
        ...body,
        requestKey: requestKey(JSON.stringify([key, body])),
      })
      patch.value = {}
      opinion.value = ''
      pending.value = null
      emit('changed', data)
    }
    actionKey = undefined
  }
  catch {
    // 请求错误由统一拦截器显示，保留表单便于再次提交。
  }
  finally {
    working.value = false
  }
}

function perform(key: string) {
  if (key === 'reject' || key === 'cancel' || key === 'recall') {
    reason.value = ''
    target.value = null
    if (key === 'reject' && props.detail)
      target.value = stages.value.find(node => node.order === props.detail!.instance.currentStepNo - 1)?.key ?? null
    pending.value = key
  }
  else if (key === 'start' || key === 'submit' || key === 'retry-assignment') {
    void execute(key)
  }
}
function confirm() {
  if (pending.value)
    void execute(pending.value)
}
</script>

<style scoped>
.workflow-page {
  display: grid;
  gap: 12px;
  grid-template-columns: minmax(0, 1fr);
}
.workflow-heading {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.workflow-heading h1 {
  font-size: 24px;
  font-weight: 650;
  overflow-wrap: anywhere;
}
.workflow-meta,
.workflow-current {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 12px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.workflow-meta {
  justify-content: space-between;
}
.workflow-current {
  margin-inline-start: auto;
}
.workflow-task-select {
  width: min(200px, 100%);
}
.opinion-row {
  display: grid;
  grid-template-columns: 112px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
}
.opinion-row > span {
  text-align: right;
  padding-top: 7px;
}
@media (max-width: 580px) {
  .opinion-row {
    grid-template-columns: 1fr;
  }
  .opinion-row > span {
    text-align: left;
  }
}
</style>
