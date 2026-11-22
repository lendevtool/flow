<template>
  <n-modal :show="true" preset="card" :title="t(todo ? '编辑待办' : '新建待办')" class="max-w-95vw w-640" :mask-closable="false" :closable="!saving" @close="emit('close')" @esc="!saving && emit('close')">
    <n-form :disabled="saving" @submit.prevent="save">
      <n-form-item :label="t('标题')" required>
        <n-input v-model:value="title" :input-props="{ 'aria-label': t('标题') }" :maxlength="200" />
      </n-form-item>
      <n-form-item :label="t('说明')">
        <n-input v-model:value="description" type="textarea" />
      </n-form-item>
      <div class="grid grid-cols-2 gap-x-12">
        <n-form-item :label="t('负责人')">
          <n-select v-model:value="assignee" :disabled="!!todo || !canAssign()" :options="options(current)" :loading="assignees.loading.value" />
        </n-form-item>
        <n-form-item :label="t('优先级')">
          <n-select v-model:value="priority" :options="priorityOptions" />
        </n-form-item>
      </div>
      <n-form-item :label="t('排期方式')">
        <n-radio-group v-model:value="scheduleMode">
          <n-radio-button value="NONE">
            {{ t('未排期') }}
          </n-radio-button>
          <n-radio-button value="FIXED">
            {{ t('指定日期') }}
          </n-radio-button>
          <n-radio-button value="TRADING">
            {{ t('交易日 T+n') }}
          </n-radio-button>
        </n-radio-group>
      </n-form-item>
      <div v-if="scheduleMode === 'FIXED'" class="grid grid-cols-1 gap-x-12 sm:grid-cols-3">
        <n-form-item :label="t('计划开始')">
          <n-date-picker v-model:formatted-value="startDate" clearable type="date" value-format="yyyy-MM-dd" class="w-full" />
        </n-form-item>
        <n-form-item :label="t('计划结束')">
          <n-date-picker v-model:formatted-value="endDate" clearable type="date" value-format="yyyy-MM-dd" class="w-full" />
        </n-form-item>
        <n-form-item :label="t('截止日期')">
          <n-date-picker v-model:formatted-value="dueDate" clearable type="date" value-format="yyyy-MM-dd" class="w-full" />
        </n-form-item>
      </div>
      <template v-else-if="scheduleMode === 'TRADING'">
        <div class="grid grid-cols-2 gap-x-12">
          <n-form-item :label="t('市场')" required>
            <n-select v-model:value="marketCode" :options="marketOptions" :loading="markets.loading.value" />
          </n-form-item>
          <n-form-item :label="t('基准交易日 T')" required>
            <n-date-picker v-model:formatted-value="tradeBaseDate" type="date" value-format="yyyy-MM-dd" class="w-full" />
          </n-form-item>
        </div>
        <div class="grid grid-cols-1 gap-x-12 sm:grid-cols-3">
          <n-form-item :label="t('开始偏移')">
            <n-input-number v-model:value="startTradeOffset" :precision="0" class="w-full" />
          </n-form-item>
          <n-form-item :label="t('结束偏移')">
            <n-input-number v-model:value="endTradeOffset" :precision="0" class="w-full" />
          </n-form-item>
          <n-form-item :label="t('截止偏移')">
            <n-input-number v-model:value="dueTradeOffset" :precision="0" class="w-full" />
          </n-form-item>
        </div>
        <div class="mb-12 flex flex-wrap items-center gap-12">
          <n-button attr-type="button" :loading="previewRequest.loading.value" @click="loadPreview">
            {{ t('预览排期') }}
          </n-button>
          <span v-if="shownDates">{{ t('计划日期') }} {{ shownDates.startDate ?? '—' }} ~ {{ shownDates.endDate ?? '—' }} · {{ t('截止日期') }} {{ shownDates.dueDate ?? '—' }}</span>
        </div>
        <p v-if="preview?.coverageStart" class="mb-12 text-sm opacity-70">
          {{ t('已校验日历覆盖') }} {{ preview.coverageStart }} ~ {{ preview.coverageEnd }}
        </p>
      </template>
      <n-collapse v-if="publicDimensions.length" class="mb-16">
        <n-collapse-item :title="t('公共维度')" name="public">
          <PublicDimensionFields v-model="dimensions" :dimensions="publicDimensions" :disabled="saving" />
        </n-collapse-item>
      </n-collapse>
      <template v-if="!todo && personalFields.length">
        <n-divider title-placement="left">
          {{ t('我的个人维度') }}
        </n-divider>
        <n-text depth="3">
          {{ t('仅自己可见') }}
        </n-text>
        <div class="grid mt-8 gap-x-12 sm:grid-cols-2">
          <PersonalDimensionFields v-model="personal" :dimensions="personalFields" />
        </div>
      </template>
      <n-alert v-if="error || previewRequest.error.value || assignees.error.value || (scheduleMode === 'TRADING' && markets.error.value)" type="error" class="mb-12">
        {{ error || previewRequest.error.value || assignees.error.value || markets.error.value }}
        <n-button v-if="assignees.error.value" text @click="load">
          {{ t('重试') }}
        </n-button>
        <n-button v-if="scheduleMode === 'TRADING' && markets.error.value" text @click="loadMarkets">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-space justify="end">
        <n-button :disabled="saving" @click="emit('close')">
          {{ t('取消') }}
        </n-button>
        <n-button type="primary" attr-type="submit" :loading="saving" :disabled="previewRequest.loading.value || stale">
          {{ t('保存') }}
        </n-button>
      </n-space>
    </n-form>
  </n-modal>
</template>

<script setup lang="ts">
import type { CalendarMarket } from '../pms/trading-calendar/api'
import type { PersonalDimension, Priority, PublicDimension, ScheduleInput, ScheduleMode, SchedulePreview, Todo, TodoWrite } from './api'
import { computed, onMounted, ref, watch } from 'vue'
import { language, t } from '@/i18n'
import { useUserStore } from '@/store'
import api from './api'
import { newWorkbenchKey, priorityLabels, workbenchError } from './model'
import PersonalDimensionFields from './PersonalDimensionFields.vue'
import PublicDimensionFields from './PublicDimensionFields.vue'
import { useAssignees, useWorkbenchRequest } from './useWorkbench'

const props = defineProps<{ todo?: Todo, date?: string, publicDimensions: PublicDimension[], personalDimensions: PersonalDimension[] }>()
const emit = defineEmits<{ close: [], saved: [], conflict: [] }>()
const user = useUserStore()
const todo = props.todo
const current = todo
  ? { id: todo.assigneeUserId, name: todo.assigneeName }
  : undefined
const title = ref(todo?.title ?? '')
const description = ref(todo?.description ?? '')
const assignee = ref<number | null>(todo?.assigneeUserId ?? user.userId ?? null)
const priority = ref<Priority>(todo?.priority ?? 'NORMAL')
const dimensions = ref({ ...todo?.dimensions })
const personalFields = computed(() => props.personalDimensions.filter(field => field.enabled))
const personal = ref<Record<number, string | null>>({})
const scheduleMode = ref<ScheduleMode>(todo?.scheduleMode ?? (props.date ? 'FIXED' : 'NONE'))
const startDate = ref(todo?.startDate ?? props.date ?? null)
const endDate = ref(todo?.endDate ?? props.date ?? null)
const dueDate = ref(todo?.dueDate ?? null)
const marketCode = ref(todo?.marketCode ?? null)
const tradeBaseDate = ref(todo?.tradeBaseDate ?? null)
const startTradeOffset = ref(todo?.startTradeOffset ?? null)
const endTradeOffset = ref(todo?.endTradeOffset ?? null)
const dueTradeOffset = ref(todo?.dueTradeOffset ?? null)
const saving = ref(false)
const stale = ref(false)
const recalculating = ref(false)
const error = ref('')
const requestKey = newWorkbenchKey()
const { assignees, canAssign, load, options } = useAssignees()
const markets = useWorkbenchRequest<CalendarMarket[]>([])
const marketOptions = computed(() => {
  const options = markets.data.value.map(market => ({ value: market.code, label: (language.value === 'en-US' && market.nameEn) || market.name }))
  if (todo?.marketCode && !options.some(option => option.value === todo.marketCode))
    options.push({ value: todo.marketCode, label: todo.marketCode })
  return options
})
const priorityOptions = computed(() => Object.entries(priorityLabels).map(([value, label]) => ({
  value,
  label: t(label),
})))
const previewRequest = useWorkbenchRequest<SchedulePreview | null>(null)
const preview = computed(() => previewRequest.data.value)

const schedule = computed<ScheduleInput>(() => {
  const fixed = scheduleMode.value === 'FIXED'
  const trading = scheduleMode.value === 'TRADING'
  // PATCH 合并字段。切换排期方式时显式清除上一个模式的日期或偏移。
  return {
    scheduleMode: scheduleMode.value,
    startDate: fixed ? startDate.value : null,
    endDate: fixed ? endDate.value : null,
    dueDate: fixed ? dueDate.value : null,
    marketCode: trading ? marketCode.value : null,
    tradeBaseDate: trading ? tradeBaseDate.value : null,
    startTradeOffset: trading ? startTradeOffset.value : null,
    endTradeOffset: trading ? endTradeOffset.value : null,
    dueTradeOffset: trading ? dueTradeOffset.value : null,
  }
})
const initialSchedule = JSON.stringify(schedule.value)
const retainsTradingDates = computed(() => todo?.scheduleMode === 'TRADING' && JSON.stringify(schedule.value) === initialSchedule)
const shownDates = computed(() => preview.value ?? (retainsTradingDates.value ? todo : null))

const loadMarkets = () => markets.run(api.markets)
watch(schedule, () => {
  previewRequest.reset()
  recalculating.value = false
  error.value = ''
})
watch(scheduleMode, (mode) => {
  if (mode === 'TRADING' && !markets.data.value.length && !markets.loading.value)
    loadMarkets()
}, { immediate: true })
onMounted(() => {
  if (!todo)
    load()
})

function validateSchedule() {
  if (scheduleMode.value === 'FIXED') {
    if (Boolean(startDate.value) !== Boolean(endDate.value) || (startDate.value && endDate.value && endDate.value < startDate.value))
      return t('请填写完整的计划起止日期，并确保结束日期不早于开始日期。')
    if (!startDate.value && !dueDate.value)
      return t('请填写计划日期或截止日期。')
  }
  if (scheduleMode.value === 'TRADING') {
    if (!marketCode.value || !tradeBaseDate.value)
      return t('请选择市场和基准交易日。')
    if ((startTradeOffset.value === null) !== (endTradeOffset.value === null)
      || (startTradeOffset.value !== null && endTradeOffset.value !== null && endTradeOffset.value < startTradeOffset.value)) {
      return t('请填写完整的计划起止偏移，并确保结束偏移不小于开始偏移。')
    }
    if (startTradeOffset.value === null && dueTradeOffset.value === null)
      return t('请填写计划偏移或截止偏移。')
  }
  return ''
}

async function loadPreview() {
  error.value = validateSchedule()
  if (!error.value) {
    recalculating.value = true
    await previewRequest.run(() => api.preview(schedule.value), '排期预览失败，请重试')
  }
}

async function save() {
  if (saving.value || stale.value)
    return
  error.value = !title.value.trim()
    ? t('请输入标题。')
    : validateSchedule()
  if (!error.value && scheduleMode.value === 'TRADING' && !preview.value && (!retainsTradingDates.value || recalculating.value))
    error.value = t('请先预览排期。')
  if (error.value)
    return

  const body: TodoWrite = {
    ...schedule.value,
    title: title.value.trim(),
    description: description.value.trim() || null,
    priority: priority.value,
    dimensions: dimensions.value,
    ...(!todo && assignee.value !== null
      ? { assigneeUserId: assignee.value }
      : {}),
    ...(preview.value
      ? {
          preview: { startDate: preview.value.startDate, endDate: preview.value.endDate, dueDate: preview.value.dueDate },
        }
      : {}),
  }
  saving.value = true
  try {
    if (todo)
      await api.edit(todo.id, { ...body, version: todo.version })
    else
      await api.create({ ...body, requestKey, personalDimensions: personal.value })
    emit('saved')
  }
  catch (cause) {
    error.value = workbenchError(cause, t('保存失败，请重试'))
    if (scheduleMode.value === 'TRADING')
      previewRequest.reset()
    if (todo && cause && typeof cause === 'object' && 'code' in cause && cause.code === 409) {
      try {
        const latest = await api.detail(todo.id)
        stale.value = latest.data.version !== todo.version
      }
      catch {
        stale.value = true
      }
      if (stale.value)
        emit('conflict')
    }
  }
  finally {
    saving.value = false
  }
}
</script>
