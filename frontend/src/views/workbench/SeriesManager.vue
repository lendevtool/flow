<template>
  <n-modal :show="true" preset="card" :title="t('周期管理')" class="max-w-95vw w-800" :closable="!saving" :mask-closable="false" @close="emit('close')" @esc="!saving && emit('close')">
    <template v-if="!draft && !stopping">
      <n-space justify="end" class="mb-12">
        <n-checkbox v-if="can('WorkbenchTodoManage')" v-model:checked="managed">
          {{ t('管理所有周期规则') }}
        </n-checkbox>
        <n-button v-if="can('WorkbenchTodoCreate')" type="primary" @click="begin()">
          {{ t('新建周期') }}
        </n-button>
      </n-space>
      <n-alert v-if="rules.error.value" type="error" class="mb-12">
        {{ rules.error.value }}
        <n-button text @click="loadRules">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-spin :show="rules.loading.value">
        <n-list v-if="rules.data.value.length">
          <n-list-item v-for="rule in rules.data.value" :key="rule.id">
            <strong>{{ rule.title }}</strong>
            <p>
              <template v-if="managed">
                {{ rule.ownerName }} ·
              </template>{{ t(frequencyLabels[rule.frequency]) }} · {{ t(ruleLabels[rule.status]) }} · {{ t('下期') }} {{ rule.nextBaseDate ?? '—' }}
            </p>
            <n-text v-if="rule.blockReason" type="warning">
              {{ t('生成阻塞') }} · {{ rule.blockedDate }} · {{ t(blockLabels[rule.blockReason] ?? rule.blockReason) }}
            </n-text>
            <template #suffix>
              <n-space>
                <n-button v-if="rule.status === 'ACTIVE' && canEditRules()" size="small" @click="begin(rule)">
                  {{ t('修改后续') }}
                </n-button>
                <n-button v-if="rule.status === 'ACTIVE' && canEditRules()" size="small" type="error" secondary @click="beginStop(rule)">
                  {{ t('停止后续') }}
                </n-button>
                <n-button v-else-if="rule.status !== 'ACTIVE' && can('WorkbenchTodoCreate')" size="small" @click="begin(rule, true)">
                  {{ t('复制规则') }}
                </n-button>
              </n-space>
            </template>
          </n-list-item>
        </n-list>
        <n-empty v-else :description="t('没有周期规则')" />
      </n-spin>
    </template>

    <n-form v-if="draft" :disabled="saving">
      <n-form-item :label="t('标题')" required>
        <n-input v-model:value="draft.title" :maxlength="200" />
      </n-form-item>
      <n-form-item :label="t('说明')">
        <n-input v-model:value="draft.description" type="textarea" />
      </n-form-item>
      <div class="grid grid-cols-2 gap-x-12">
        <n-form-item :label="t('负责人')">
          <n-select v-model:value="draft.assigneeUserId" :disabled="!canAssign()" :options="options()" :loading="assignees.loading.value" />
        </n-form-item>
        <n-form-item :label="t('优先级')">
          <n-select v-model:value="draft.priority" :options="priorityOptions" />
        </n-form-item>
        <n-form-item :label="t('周期类型')">
          <n-select v-model:value="draft.frequency" :options="frequencyOptions" />
        </n-form-item>
        <n-form-item :label="t('周期间隔')">
          <n-input-number v-model:value="draft.intervalValue" :min="1" :precision="0" class="w-full" />
        </n-form-item>
        <n-form-item :label="t(draft.frequency === 'TRADING_DAILY' ? '首个基准交易日 T' : '首期日期')" required>
          <n-date-picker v-model:formatted-value="draft.anchorDate" value-format="yyyy-MM-dd" type="date" class="w-full" />
        </n-form-item>
        <n-form-item :label="t('结束日期')">
          <n-date-picker v-model:formatted-value="draft.untilDate" value-format="yyyy-MM-dd" type="date" clearable class="w-full" />
        </n-form-item>
      </div>
      <n-form-item v-if="draft.frequency === 'WEEKLY'" :label="t('重复星期')">
        <n-checkbox-group v-model:value="weekdays">
          <n-space>
            <n-checkbox v-for="(label, index) in dayLabels" :key="label" :value="2 ** index">
              {{ t(label) }}
            </n-checkbox>
          </n-space>
        </n-checkbox-group>
      </n-form-item>
      <n-form-item v-if="draft.frequency === 'MONTHLY' || draft.frequency === 'YEARLY'" :label="t('日期规则')">
        <n-radio-group v-model:value="draft.monthDayMode">
          <n-radio-button value="ANCHOR">
            {{ t('与首期同日') }}
          </n-radio-button>
          <n-radio-button value="MONTH_END">
            {{ t('月末') }}
          </n-radio-button>
        </n-radio-group>
      </n-form-item>
      <template v-if="draft.frequency === 'TRADING_DAILY'">
        <n-form-item :label="t('市场')" required>
          <n-select v-model:value="draft.marketCode" :options="marketOptions" :loading="markets.loading.value" />
        </n-form-item>
        <div class="grid grid-cols-3 gap-x-12">
          <n-form-item :label="t('开始偏移')">
            <n-input-number v-model:value="draft.startTradeOffset" :precision="0" class="w-full" />
          </n-form-item>
          <n-form-item :label="t('结束偏移')">
            <n-input-number v-model:value="draft.endTradeOffset" :precision="0" class="w-full" />
          </n-form-item>
          <n-form-item :label="t('截止偏移')">
            <n-input-number v-model:value="draft.dueTradeOffset" :precision="0" class="w-full" />
          </n-form-item>
        </div>
      </template>
      <div v-else class="grid grid-cols-2 gap-x-12">
        <n-form-item :label="t('计划天数')">
          <n-input-number v-model:value="draft.durationDays" :min="1" :precision="0" class="w-full" />
        </n-form-item>
        <n-form-item :label="t('截止偏移天数')">
          <n-input-number v-model:value="draft.dueOffsetDays" :precision="0" class="w-full" />
        </n-form-item>
      </div>
      <n-collapse v-if="publicDimensions.length" class="mb-16">
        <n-collapse-item :title="t('公共维度')" name="public">
          <PublicDimensionFields v-model="draft.dimensions" :dimensions="publicDimensions" />
        </n-collapse-item>
      </n-collapse>
    </n-form>

    <n-alert v-if="stopping" type="warning" class="mb-12">
      {{ t('停止「{0}」的后续生成？', stopping.title) }}
    </n-alert>
    <n-alert v-if="assignees.error.value && draft" type="error" class="mb-12">
      {{ assignees.error.value }}
      <n-button text @click="load">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <n-alert v-if="markets.error.value && draft?.frequency === 'TRADING_DAILY'" type="error" class="mb-12">
      {{ markets.error.value }}
      <n-button text @click="loadMarkets">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <n-alert v-if="error" type="error" class="mb-12">
      {{ error }}
    </n-alert>
    <div v-if="schedule" class="mb-12">
      <strong>{{ t('后续三期') }}</strong>
      <p v-if="schedule.coverageStart">
        {{ t('日历覆盖') }} {{ schedule.coverageStart }} ~ {{ schedule.coverageEnd }}
      </p>
      <n-alert v-if="schedule.blockReason" type="warning">
        {{ schedule.blockedDate }} · {{ t(blockLabels[schedule.blockReason] ?? schedule.blockReason) }}
      </n-alert>
      <n-empty v-if="!schedule.occurrences.length" :description="t('没有可预览的期次')" />
      <n-list v-else>
        <n-list-item v-for="item in schedule.occurrences" :key="item.seriesBaseDate">
          {{ item.seriesBaseDate }} · {{ t('计划日期') }} {{ item.startDate ?? '—' }} ~ {{ item.endDate ?? '—' }} · {{ t('截止日期') }} {{ item.dueDate ?? '—' }}
        </n-list-item>
      </n-list>
    </div>
    <div v-if="impact" class="mb-12">
      <strong>{{ t('受影响的待办（{0}）', impact.affected.length) }}</strong>
      <n-list v-if="impact.affected.length" class="max-h-200 overflow-auto">
        <n-list-item v-for="item in impact.affected" :key="item.id">
          {{ item.occurrenceDate }} · {{ item.title }}
        </n-list-item>
      </n-list>
    </div>
    <n-space v-if="draft || stopping" justify="end">
      <n-button :disabled="saving" @click="resetEditor">
        {{ t('返回规则列表') }}
      </n-button>
      <n-button :loading="saving" @click="preview">
        {{ t(editing || stopping ? '预览影响' : '预览排期') }}
      </n-button>
      <n-button type="primary" :loading="saving" :disabled="!prepared" @click="save">
        {{ t(stopping ? '确认停止' : '保存') }}
      </n-button>
    </n-space>
  </n-modal>
</template>

<script setup lang="ts">
import type { CalendarMarket } from '../pms/trading-calendar/api'
import type { Series, SeriesImpact, SeriesPreview, SeriesWrite } from './advanced-api'
import type { PublicDimension } from './api'
import { computed, onMounted, ref, watch } from 'vue'
import { language, t } from '@/i18n'
import { useUserStore } from '@/store'
import advanced from './advanced-api'
import api from './api'
import { formatBusinessToday, priorityLabels, workbenchError } from './model'
import PublicDimensionFields from './PublicDimensionFields.vue'
import { useAssignees, useWorkbenchPermission, useWorkbenchRequest } from './useWorkbench'

type Draft = Omit<SeriesWrite, 'intervalValue' | 'anchorDate'> & { intervalValue: number | null, anchorDate: string | null, description: string }
defineProps<{ publicDimensions: PublicDimension[] }>()
const emit = defineEmits<{ close: [], saved: [] }>()
const user = useUserStore()
const can = useWorkbenchPermission()
const managed = ref(false)
function canEditRules() {
  return managed.value
    ? can('WorkbenchTodoManage')
    : can('WorkbenchTodoCreate')
}
const { assignees, canAssign, load, options } = useAssignees(() => false, () => managed.value)
const rules = useWorkbenchRequest<Series[]>([])
const markets = useWorkbenchRequest<CalendarMarket[]>([])
const draft = ref<Draft | null>(null)
const editing = ref<Series | null>(null)
const stopping = ref<Series | null>(null)
const weekdays = ref<number[]>([])
const schedule = ref<SeriesPreview | null>(null)
const impact = ref<SeriesImpact | null>(null)
const prepared = ref<{ body?: SeriesWrite, token?: string } | null>(null)
const saving = ref(false)
const error = ref('')
const frequencyLabels = {
  DAILY: '每天',
  WEEKLY: '每周',
  MONTHLY: '每月',
  YEARLY: '每年',
  TRADING_DAILY: '交易日周期',
}
const blockLabels: Record<string, string> = {
  COVERAGE_MISSING: '日历缺少交易日数据',
  BASE_CLOSED: '基准日休市',
  MARKET_DISABLED: '市场已停用',
  ASSIGNEE_INVALID: '负责人不可用',
  SEARCH_LIMIT: '超出查找范围',
}
const ruleLabels = { ACTIVE: '运行中', STOPPED: '已停止', FINISHED: '已结束' }
const dayLabels = [
  '周一',
  '周二',
  '周三',
  '周四',
  '周五',
  '周六',
  '周日',
]
const frequencyOptions = computed(() => Object.entries(frequencyLabels).map(([value, label]) => ({
  value,
  label: t(label),
})))
const priorityOptions = computed(() => Object.entries(priorityLabels).map(([value, label]) => ({
  value,
  label: t(label),
})))
const marketOptions = computed(() => {
  const options = markets.data.value.map(item => ({ value: item.code, label: (language.value === 'en-US' && item.nameEn) || item.name }))
  const code = editing.value?.marketCode
  if (code && !options.some(option => option.value === code))
    options.push({ value: code, label: code })
  return options
})
const loadRules = () => rules.run(() => advanced.series(managed.value))
const loadMarkets = () => markets.run(api.markets)
onMounted(loadRules)
watch(managed, loadRules)

watch(() => draft.value?.frequency, (frequency) => {
  if (frequency === 'TRADING_DAILY' && !markets.data.value.length && !markets.loading.value)
    loadMarkets()
})
watch([draft, weekdays], clearPreview, { deep: true })

function clearPreview() {
  error.value = ''
  schedule.value = null
  impact.value = null
  prepared.value = null
}

function resetEditor() {
  draft.value = null
  editing.value = null
  stopping.value = null
  clearPreview()
}

function begin(rule?: Series, copy = false) {
  clearPreview()
  editing.value = copy
    ? null
    : rule ?? null
  draft.value = {
    title: rule?.title ?? '',
    description: rule?.description ?? '',
    assigneeUserId: rule?.assigneeUserId ?? user.userId ?? null,
    priority: rule?.priority ?? 'NORMAL',
    frequency: rule?.frequency ?? 'DAILY',
    intervalValue: rule?.intervalValue ?? 1,
    anchorDate: rule?.anchorDate ?? formatBusinessToday(),
    untilDate: rule?.untilDate ?? null,
    monthDayMode: rule?.monthDayMode ?? 'ANCHOR',
    durationDays: rule ? rule.durationDays : 1,
    dueOffsetDays: rule?.dueOffsetDays ?? null,
    marketCode: rule?.marketCode ?? null,
    startTradeOffset: rule?.startTradeOffset ?? null,
    endTradeOffset: rule?.endTradeOffset ?? null,
    dueTradeOffset: rule?.dueTradeOffset ?? null,
    dimensions: { ...rule?.dimensions },
  }
  weekdays.value = dayLabels.map((_, index) => 2 ** index).filter(bit => bit & (rule?.weekdaysMask ?? 0))
  load()
}

function beginStop(rule: Series) {
  clearPreview()
  stopping.value = rule
  preview()
}

function serialize(): SeriesWrite | null {
  const form = draft.value
  if (!form?.title.trim() || !form.anchorDate || !form.intervalValue) {
    error.value = t('请输入标题、周期间隔和首期日期。')
    return null
  }
  if (form.untilDate && form.untilDate < form.anchorDate) {
    error.value = t('结束日期不能早于开始日期。')
    return null
  }
  const common = {
    title: form.title.trim(),
    description: form.description.trim() || null,
    assigneeUserId: form.assigneeUserId,
    priority: form.priority,
    frequency: form.frequency,
    intervalValue: form.intervalValue,
    anchorDate: form.anchorDate,
    untilDate: form.untilDate,
    dimensions: { ...form.dimensions },
  }
  if (form.frequency === 'TRADING_DAILY') {
    const { marketCode, startTradeOffset, endTradeOffset, dueTradeOffset } = form
    return {
      ...common,
      marketCode,
      startTradeOffset,
      endTradeOffset,
      dueTradeOffset,
    }
  }
  const { durationDays, dueOffsetDays } = form
  return {
    ...common,
    durationDays,
    dueOffsetDays,
    ...(form.frequency === 'WEEKLY'
      ? {
          weekdaysMask: weekdays.value.reduce((mask, bit) => mask | bit, 0),
        }
      : {}),
    ...(form.frequency === 'MONTHLY' || form.frequency === 'YEARLY'
      ? { monthDayMode: form.monthDayMode }
      : {}),
  }
}

async function preview() {
  if (saving.value)
    return
  clearPreview()
  const body = stopping.value
    ? undefined
    : serialize()
  if (!stopping.value && !body)
    return
  saving.value = true
  try {
    const rule = editing.value ?? stopping.value
    if (rule) {
      const { data } = await advanced.impact(rule.id, body
        ? { action: 'EDIT', rule: body }
        : { action: 'STOP' }, managed.value)
      impact.value = data
      schedule.value = data.schedule ?? null
      prepared.value = { body: body ?? undefined, token: data.token }
    }
    else if (body) {
      const { data } = await advanced.previewSeries(body)
      schedule.value = data
      if (!data.blockReason)
        prepared.value = { body }
    }
  }
  catch (cause) {
    error.value = workbenchError(cause, t('预览失败，请重试'))
  }
  finally {
    saving.value = false
  }
}

async function save() {
  const change = prepared.value
  if (!change || saving.value)
    return
  saving.value = true
  error.value = ''
  try {
    if (stopping.value && change.token)
      await advanced.stopSeries(stopping.value.id, change.token, managed.value)
    else if (editing.value && change.token && change.body)
      await advanced.editSeries(editing.value.id, change.token, change.body, managed.value)
    else if (change.body)
      await advanced.createSeries(change.body)
    resetEditor()
    emit('saved')
    await loadRules()
  }
  catch (cause) {
    clearPreview()
    error.value = workbenchError(cause, t('保存失败，请重试'))
  }
  finally {
    saving.value = false
  }
}
</script>
