<template>
  <n-drawer :show="true" :width="540" style="max-width: 100vw" :mask-closable="!saving" @update:show="show => !show && emit('close')">
    <n-drawer-content :title="t('待办详情')" :closable="!saving">
      <n-alert v-if="detail.error.value" type="error" class="mb-12">
        {{ detail.error.value }}
        <n-button text @click="refresh">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-spin :show="detail.loading.value">
        <template v-if="todo">
          <h2 class="mb-8 break-words text-20 font-semibold">
            {{ todo.title }}
          </h2>
          <n-space class="mb-16">
            <n-tag :type="todo.status === 'DONE' ? 'success' : 'default'">
              {{ t(statusLabels[todo.status]) }}
            </n-tag>
            <n-tag :type="todo.priority === 'HIGH' ? 'warning' : 'default'">
              {{ t(priorityLabels[todo.priority]) }}
            </n-tag>
            <n-tag>{{ t(originLabels[todo.origin]) }}</n-tag>
          </n-space>
          <p v-if="todo.description" class="mb-16 whitespace-pre-wrap break-words">
            {{ todo.description }}
          </p>
          <TodoDetailDescriptions :todo="todo">
            <template #before>
              <n-descriptions-item :label="t('创建人')">
                {{ todo.ownerName || todo.sourceLabel || todo.sourceModule || t('系统') }}
              </n-descriptions-item>
            </template>
            <n-descriptions-item v-if="todo.scheduleMode === 'TRADING'" :label="t('交易日 T+n')" :span="2">
              {{ todo.marketCode }} · T = {{ todo.tradeBaseDate }} · {{ offsetSummary }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('创建时间')">
              {{ displayTime(todo.createdAt) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('待办编号')">
              {{ todo.id }}
            </n-descriptions-item>
            <n-descriptions-item v-if="todo.closedAt" :label="t('关闭时间')">
              {{ displayTime(todo.closedAt) }}
            </n-descriptions-item>
          </TodoDetailDescriptions>

          <template v-if="shared.length">
            <n-divider title-placement="left">
              {{ t('公共维度') }}
            </n-divider>
            <n-descriptions :column="2" label-placement="top">
              <n-descriptions-item v-for="field in shared" :key="field.slot" :label="field.name">
                {{ field.value }}
              </n-descriptions-item>
            </n-descriptions>
          </template>

          <template v-if="todo.origin === 'FLOW' && todo.businessId && !newWorkflow">
            <n-divider title-placement="left">
              {{ t('流程进度') }}
            </n-divider>
            <n-spin :show="flow.loading.value">
              <n-alert v-if="flow.error.value" type="error">
                {{ flow.error.value }}
                <n-button text @click="loadFlow(todo.businessId)">
                  {{ t('重试') }}
                </n-button>
              </n-alert>
              <n-timeline v-if="flow.data.value">
                <n-timeline-item
                  v-for="step in flow.data.value.steps"
                  :key="step.stepNo"
                  :title="step.title"
                  :type="step.status === 'DONE' ? 'success' : step.stepNo === flow.data.value.currentStepNo ? 'info' : 'default'"
                >
                  <n-space align="center">
                    {{ step.assigneeName || '—' }} · {{ t(stepLabels[step.status] ?? step.status) }}
                    <n-button v-if="canAssignStep(step.stepNo)" text type="primary" size="small" @click="prepare('assignStep', step.stepNo)">
                      {{ t('改派') }}
                    </n-button>
                  </n-space>
                </n-timeline-item>
              </n-timeline>
            </n-spin>
          </template>

          <template v-if="personalFields.length">
            <n-divider title-placement="left">
              {{ t('我的个人维度') }}
            </n-divider>
            <n-text depth="3">
              {{ t('仅自己可见') }}
            </n-text>
            <n-form :disabled="saving" class="mt-8">
              <PersonalDimensionFields v-model="personal" :dimensions="personalFields" :original="todo.personalDimensions" />
              <n-button :disabled="!Object.keys(personalChanges).length" :loading="saving" @click="savePersonal">
                {{ t('保存个人维度') }}
              </n-button>
            </n-form>
          </template>
        </template>
      </n-spin>
      <n-alert v-if="error" type="error" class="mt-12">
        {{ error }}
      </n-alert>

      <template v-if="todo" #footer>
        <n-space>
          <n-button v-if="allowed('edit')" :disabled="saving" @click="emit('edit', todo)">
            {{ t(todo.seriesId ? '编辑本次' : '编辑') }}
          </n-button>
          <n-button v-if="allowed('assign')" :disabled="saving" @click="prepare('assign')">
            {{ t('改派') }}
          </n-button>
          <n-button v-if="allowed('start')" :loading="saving" @click="perform('start')">
            {{ t('开始处理') }}
          </n-button>
          <n-button v-if="!newWorkflow && (allowed('complete') || allowed('completeStep'))" :disabled="todo.origin === 'FLOW' && !flow.data.value" type="primary" :loading="saving" @click="perform('complete')">
            {{ t(todo.origin === 'FLOW' ? '完成本节点' : '完成') }}
          </n-button>
          <n-button v-if="todo.origin === 'FLOW' ? canManageFlow() : allowed('cancel')" type="error" secondary :disabled="saving" @click="prepare('cancel')">
            {{ t(todo.origin === 'FLOW' ? '取消流程' : todo.seriesId ? '取消本次' : '取消待办') }}
          </n-button>
          <RouterLink v-if="sourceUrl" :to="sourceUrl">{{ t(newWorkflow ? '打开流程' : '打开来源') }}</RouterLink>
        </n-space>
      </template>
    </n-drawer-content>
  </n-drawer>

  <n-modal v-if="pending" :show="true" preset="card" :title="t(pending === 'cancel' ? '填写取消原因' : '选择新负责人')" class="max-w-95vw w-440" :closable="!saving" :mask-closable="false" @close="pending = null" @esc="!saving && (pending = null)">
    <n-form :disabled="saving" @submit.prevent="confirm">
      <n-form-item v-if="pending === 'cancel'" :label="t('取消原因')" required>
        <n-input v-model:value="reason" type="textarea" :maxlength="500" />
      </n-form-item>
      <n-form-item v-else :label="t('负责人')" required>
        <n-select v-model:value="newAssignee" filterable :options="options()" :loading="assignees.loading.value" />
      </n-form-item>
      <n-alert v-if="error || assignees.error.value" type="error" class="mb-12">
        {{ error || assignees.error.value }}
        <n-button v-if="assignees.error.value && pending !== 'cancel'" text @click="load">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-space justify="end">
        <n-button :disabled="saving" @click="pending = null">
          {{ t('取消') }}
        </n-button>
        <n-button type="primary" attr-type="submit" :loading="saving" :disabled="pending === 'cancel' ? !reason.trim() : newAssignee === null">
          {{ t('确认') }}
        </n-button>
      </n-space>
    </n-form>
  </n-modal>
</template>

<script setup lang="ts">
import type { Flow } from './advanced-api'
import type { PersonalDimension, PublicDimension, Todo, TodoAction } from './api'
import { computed, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { language, t } from '@/i18n'
import { useUserStore } from '@/store'
import advanced from './advanced-api'
import api from './api'
import { originLabels, priorityLabels, statusLabels, workbenchError } from './model'
import PersonalDimensionFields from './PersonalDimensionFields.vue'
import TodoDetailDescriptions from './TodoDetailDescriptions.vue'
import { useAssignees, useWorkbenchPermission, useWorkbenchRequest } from './useWorkbench'

const props = defineProps<{ id: number, publicDimensions: PublicDimension[], personalDimensions: PersonalDimension[] }>()
const emit = defineEmits<{ close: [], edit: [todo: Todo], changed: [] }>()
const detail = useWorkbenchRequest<Todo | null>(null)
const flow = useWorkbenchRequest<Flow | null>(null)
const todo = detail.data
const personal = ref<Record<number, string | null>>({})
const saving = ref(false)
const error = ref('')
const pending = ref<'cancel' | 'assign' | 'assignStep' | null>(null)
const reason = ref('')
const newAssignee = ref<number | null>(null)
const newStep = ref<number | null>(null)
const user = useUserStore()
const can = useWorkbenchPermission()
const { assignees, load, options } = useAssignees(() => todo.value?.origin === 'FLOW')
const stepLabels: Record<string, string> = {
  ACTIVE: '当前节点',
  OPEN: '当前节点',
  DONE: '已完成',
  CANCELLED: '已取消',
  WAITING: '未到达',
  PENDING: '未到达',
  NOT_REACHED: '未到达',
}

const shared = computed(() => props.publicDimensions.flatMap((field) => {
  const id = todo.value?.dimensions[field.slot]
  if (id == null)
    return []
  return [
    { ...field, value: field.options.find(option => option.id === id)?.name ?? String(id) },
  ]
}))
const personalFields = computed(() => props.personalDimensions.filter(field => field.enabled || todo.value?.personalDimensions[field.slot]))
const personalChanges = computed(() => Object.fromEntries(personalFields.value.flatMap(({ slot }) => {
  const value = personal.value[slot]?.trim() || null
  return value === (todo.value?.personalDimensions[slot] ?? null)
    ? []
    : [
        [slot, value],
      ]
})))
const offsetSummary = computed(() => [
  ['计划开始', todo.value?.startTradeOffset],
  ['计划结束', todo.value?.endTradeOffset],
  ['截止日期', todo.value?.dueTradeOffset],
].filter(([, value]) => value != null).map(([label, value]) => `${t(String(label))} T${Number(value) >= 0
  ? '+'
  : ''}${value}`).join(' · '))
const newWorkflow = computed(() => todo.value?.origin === 'FLOW' && todo.value.sourceUrl?.startsWith('/workbench/workflow-task/'))
const sourceUrl = computed(() => {
  const value = todo.value?.sourceUrl
  return value?.startsWith('/') && !value.startsWith('//')
    ? value
    : null
})

function displayTime(value: string) {
  const date = new Date(/(?:Z|[+-]\d{2}:?\d{2})$/i.test(value)
    ? value
    : `${value}Z`)
  if (Number.isNaN(date.getTime()))
    return value
  return new Intl.DateTimeFormat(language.value, { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Shanghai' }).format(date)
}

function allowed(action: string) {
  return todo.value?.allowedActions?.some(value => value.toLowerCase() === action.toLowerCase()) ?? false
}

function canManageFlow(action = 'cancel') {
  const current = flow.data.value
  return current?.status === 'RUNNING' && (current.allowedActions?.some(value => value.toLowerCase() === action)
    ?? (current.ownerUserId === user.userId || can('WorkbenchTodoFlowManage')))
}

function canAssignStep(stepNo: number) {
  return canManageFlow('assign') && stepNo >= (flow.data.value?.currentStepNo ?? 0)
}

function loadFlow(id: string | null) {
  flow.reset()
  if (id)
    return flow.run(() => advanced.flow(id))
}

async function refresh() {
  detail.reset()
  const result = await detail.run(() => api.detail(props.id))
  if (result) {
    personal.value = { ...result.personalDimensions }
    await loadFlow(result.origin === 'FLOW' && !result.sourceUrl?.startsWith('/workbench/workflow-task/')
      ? result.businessId
      : null)
  }
}

watch(() => props.id, () => {
  flow.reset()
  pending.value = null
  error.value = ''
  refresh()
}, { immediate: true })

function prepare(action: 'cancel' | 'assign' | 'assignStep', stepNo?: number) {
  error.value = ''
  reason.value = ''
  newAssignee.value = null
  newStep.value = stepNo ?? null
  pending.value = action
  if (action !== 'cancel')
    load()
}

async function perform(action: TodoAction | 'assignStep') {
  const record = todo.value
  if (!record || saving.value)
    return
  saving.value = true
  error.value = ''
  try {
    const process = flow.data.value
    const assignee = newAssignee.value
    if (record.origin === 'FLOW' && process) {
      if (action === 'complete')
        await advanced.completeStep(process.id, record.id, process.version)
      else if (action === 'cancel')
        await advanced.cancelFlow(process.id, process.version, reason.value.trim())
      else if (action === 'assignStep' && newStep.value !== null && assignee !== null)
        await advanced.assignStep(process.id, process.version, newStep.value, assignee)
    }
    else if (action !== 'assignStep') {
      await api.action(record.id, action, {
        version: record.version,
        ...(action === 'cancel'
          ? {
              reason: reason.value.trim(),
            }
          : {}),
        ...(action === 'assign' && assignee !== null
          ? { assigneeUserId: assignee }
          : {}),
      })
    }
    pending.value = null
    emit('changed')
    await refresh()
  }
  catch (cause) {
    error.value = workbenchError(cause, t('操作失败，请重试'))
    if (cause && typeof cause === 'object' && 'code' in cause && cause.code === 409) {
      pending.value = null
      await refresh()
    }
  }
  finally {
    saving.value = false
  }
}

function confirm() {
  if (pending.value)
    perform(pending.value)
}

async function savePersonal() {
  if (!todo.value || saving.value || !Object.keys(personalChanges.value).length)
    return
  saving.value = true
  error.value = ''
  try {
    await api.markPersonal(todo.value.id, personalChanges.value)
    emit('changed')
    await refresh()
  }
  catch (cause) {
    error.value = workbenchError(cause, t('保存失败，请重试'))
  }
  finally {
    saving.value = false
  }
}

defineExpose({ refresh })
</script>
