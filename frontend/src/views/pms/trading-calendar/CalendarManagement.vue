<template>
  <n-modal
    :show="true"
    preset="card"
    :title="title"
    :mask-closable="!busy"
    :closable="!busy"
    :close-on-esc="!busy"
    style="width: min(610px, 94vw)"
    @update:show="close"
  >
    <n-form :disabled="formDisabled" :show-feedback="false" class="editor-form">
      <template v-if="kind === 'initialize'">
        <n-alert type="info">
          {{ t('将初始化 {0} 年的日历：周一至周五为交易，周六和周日为休市，仅补齐缺失日期。交易所节假日需通过导入或手动编辑调整。', year) }}
        </n-alert>
        <n-form-item :label="t('目标市场')" required>
          <n-select v-model:value="targetCodes" :options="marketOptions" multiple />
        </n-form-item>
      </template>

      <template v-else-if="kind === 'batch'">
        <n-form-item :label="t('目标市场')" required>
          <n-select v-model:value="targetCodes" :options="marketOptions" multiple />
        </n-form-item>
        <n-form-item :label="t('日期选择方式')" required>
          <n-radio-group v-model:value="dateMode">
            <n-space>
              <n-radio value="DATES">
                {{ t('指定日期') }}
              </n-radio>
              <n-radio value="RANGE">
                {{ t('连续日期范围') }}
              </n-radio>
            </n-space>
          </n-radio-group>
        </n-form-item>
        <n-form-item v-if="dateMode === 'RANGE'" :label="t('日期范围（含首尾）')" required>
          <n-date-picker
            :formatted-value="range"
            value-format="yyyy-MM-dd"
            type="daterange"
            clearable
            class="w-full"
            @update:formatted-value="setRange"
          />
        </n-form-item>
        <n-form-item v-else :label="t('所选日期')" required>
          <div class="date-field">
            <div class="date-actions">
              <n-date-picker
                :formatted-value="null"
                value-format="yyyy-MM-dd"
                :placeholder="t('添加日期')"
                @update:formatted-value="addDate"
              />
              <n-button text :disabled="!pickedDates.length" @click="pickedDates = []">
                {{ t('清空选择') }}
              </n-button>
            </div>
            <div v-if="pickedDates.length" class="date-tags">
              <n-tag v-for="date in pickedDates" :key="date" size="small" :closable="!formDisabled" @close="removeDate(date)">
                {{ date }}
              </n-tag>
            </div>
          </div>
        </n-form-item>
        <n-form-item :label="t('交易状态')" required>
          <n-radio-group v-model:value="status">
            <n-space>
              <n-radio value="TRADING">
                {{ t('交易') }}
              </n-radio>
              <n-radio value="CLOSED">
                {{ t('休市') }}
              </n-radio>
            </n-space>
          </n-radio-group>
        </n-form-item>
        <n-form-item :label="t('节假日名称')">
          <n-radio-group v-model:value="holidayMode">
            <n-space>
              <n-radio value="KEEP">
                {{ t('保持原值') }}
              </n-radio>
              <n-radio value="SET">
                {{ t('设置名称') }}
              </n-radio>
              <n-radio value="CLEAR">
                {{ t('清除名称') }}
              </n-radio>
            </n-space>
          </n-radio-group>
        </n-form-item>
        <n-form-item v-if="holidayMode === 'SET'" :label="t('新节假日名称')" required>
          <n-input v-model:value="holidayName" :maxlength="100" />
        </n-form-item>
      </template>

      <template v-else>
        <n-form-item :label="t('导入方式')" required>
          <n-radio-group v-model:value="importMode">
            <n-space>
              <n-radio value="SINGLE">
                {{ t('单市场模板') }}
              </n-radio>
              <n-radio value="MULTI">
                {{ t('多市场模板') }}
              </n-radio>
            </n-space>
          </n-radio-group>
        </n-form-item>
        <n-form-item v-if="importMode === 'SINGLE'" :label="t('目标市场')" required>
          <n-select v-model:value="importMarket" :options="marketOptions" />
        </n-form-item>
        <p class="import-columns">
          {{ t(importMode === 'SINGLE' ? '模板列：日期、是否交易日、节假日、备注。交易填 1，休市填 0。' : '模板列：市场代码、日期、是否交易日、节假日、备注。交易填 1，休市填 0。') }}
        </p>
        <div class="date-actions">
          <n-button :loading="downloading" :disabled="busy" @click="downloadTemplate">
            {{ t('下载模板') }}
          </n-button>
          <input ref="fileInput" type="file" accept=".xlsx" hidden :disabled="busy" @change="chooseFile">
          <n-button :disabled="busy" @click="fileInput?.click()">
            {{ t('选择 Excel 文件') }}
          </n-button>
          <span v-if="file" class="file-name">{{ file.name }}</span>
        </div>
        <n-alert v-if="downloadError" type="error" role="alert">
          {{ downloadError }}
        </n-alert>
      </template>

      <n-alert v-if="error" type="error" role="alert">
        {{ error }}
      </n-alert>
      <template v-if="result">
        <n-alert :type="result.errors.length ? 'warning' : 'info'" :title="t('预览结果')" aria-live="polite">
          {{ t('新增 {0} 条 · 修改 {1} 条 · 不变 {2} 条', result.added, result.changed, result.unchanged) }}
        </n-alert>
        <ul v-if="result.errors.length" class="validation-errors">
          <li v-for="(issue, index) in result.errors" :key="index">
            {{ issue.row && issue.row > 0 ? t('第 {0} 行：{1}', issue.row, issue.message) : issue.message }}
          </li>
        </ul>
      </template>
    </n-form>
    <template #footer>
      <n-space justify="end">
        <n-button :disabled="busy" @click="close">
          {{ t('取消') }}
        </n-button>
        <n-button
          v-if="kind === 'import' || (kind === 'initialize' && !fingerprint) || error || result?.errors.length"
          :loading="running === 'preview'"
          :disabled="!payload || busy"
          @click="run('preview')"
        >
          {{ t('预览') }}
        </n-button>
        <n-button
          type="primary"
          :loading="running === 'apply' || (kind === 'batch' && running === 'preview')"
          :disabled="!fingerprint || busy"
          @click="run('apply')"
        >
          {{ t('确认应用') }}
        </n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import type { CalendarBatch, CalendarImport, CalendarImportMode, CalendarInitialization, CalendarMarket, CalendarPreview, HolidayMode } from './api'
import type { ManagementKind } from './model'
import { computed, ref, watch } from 'vue'
import { t } from '@/i18n'
import api from './api'
import { calendarError, marketName, previewFingerprint } from './model'

const props = defineProps<{
  kind: ManagementKind
  markets: CalendarMarket[]
  marketCodes: string[]
  year: number
  dates: string[]
  initialStatus?: boolean
}>()
const emit = defineEmits<{ close: [], applied: [] }>()

type Phase = 'preview' | 'apply'
type Operation
  = | { kind: 'initialize', data: CalendarInitialization }
    | { kind: 'batch', data: CalendarBatch }
    | { kind: 'import', data: CalendarImport }

const targetCodes = ref([...props.marketCodes])
const pickedDates = ref([...props.dates])
const dateMode = ref<'DATES' | 'RANGE'>(props.dates.length ? 'DATES' : 'RANGE')
const range = ref<[string, string] | null>(null)
const status = ref<'TRADING' | 'CLOSED' | null>(props.initialStatus === undefined
  ? null
  : props.initialStatus ? 'TRADING' : 'CLOSED')
const holidayMode = ref<HolidayMode>('KEEP')
const holidayName = ref('')
const importMode = ref<CalendarImportMode>('SINGLE')
const importMarket = ref<string | null>(props.marketCodes[0] ?? null)
const fileInput = ref<HTMLInputElement | null>(null)
const file = ref<File | null>(null)
const result = ref<CalendarPreview | null>(null)
const running = ref<Phase | null>(null)
const busy = computed(() => running.value !== null)
const formDisabled = computed(() => running.value === 'apply' || (props.kind !== 'batch' && busy.value))
let previewPending = false
const downloading = ref(false)
const downloadError = ref('')
const error = ref('')
const fingerprint = computed(() => previewFingerprint(result.value))
const title = computed(() => t({
  initialize: '初始化交易日历',
  batch: '设置交易日历',
  import: '导入交易日历',
}[props.kind]))
const marketOptions = computed(() => props.markets.map(market => ({
  label: `${marketName(market)} (${market.code})`,
  value: market.code,
})))

const payload = computed<Operation | null>(() => {
  if (props.kind === 'initialize') {
    return targetCodes.value.length
      ? {
          kind: 'initialize',
          data: {
            year: props.year,
            marketCodes: [...targetCodes.value],
          },
        }
      : null
  }

  if (props.kind === 'import') {
    if (!file.value)
      return null
    if (importMode.value === 'MULTI') {
      return {
        kind: 'import',
        data: { mode: 'MULTI', file: file.value },
      }
    }
    return importMarket.value
      ? {
          kind: 'import',
          data: { mode: 'SINGLE', marketCode: importMarket.value, file: file.value },
        }
      : null
  }

  if (!targetCodes.value.length || !status.value || (holidayMode.value === 'SET' && !holidayName.value.trim()))
    return null

  const values = {
    marketCodes: [...targetCodes.value],
    isTradingDay: status.value === 'TRADING',
    holidayMode: holidayMode.value,
    holidayName: holidayMode.value === 'SET'
      ? holidayName.value.trim()
      : undefined,
  }
  if (dateMode.value === 'RANGE') {
    return range.value
      ? {
          kind: 'batch',
          data: { ...values, startDate: range.value[0], endDate: range.value[1] },
        }
      : null
  }
  return pickedDates.value.length
    ? {
        kind: 'batch',
        data: {
          ...values,
          dates: [...pickedDates.value],
        },
      }
    : null
})

watch(payload, (operation, previous, onCleanup) => {
  result.value = null
  error.value = ''
  if (!operation || operation.kind === 'import')
    return

  // 编辑时等用户暂停输入再预览，并始终用最新的表单数据。
  const timer = operation.kind === 'batch' && previous !== undefined
    ? setTimeout(requestAutoPreview, 250)
    : undefined
  onCleanup(() => {
    clearTimeout(timer)
    previewPending = false
  })
  if (timer === undefined)
    requestAutoPreview()
}, { immediate: true, flush: 'sync' })

function requestAutoPreview() {
  if (busy.value)
    previewPending = true
  else
    void run('preview')
}

function close() {
  if (!busy.value)
    emit('close')
}

function setRange(value: string | [string, string] | null) {
  range.value = Array.isArray(value)
    ? value
    : null
}

function addDate(value: string | [string, string] | null) {
  if (typeof value === 'string' && !pickedDates.value.includes(value))
    pickedDates.value = [...pickedDates.value, value].sort()
}

function removeDate(date: string) {
  pickedDates.value = pickedDates.value.filter(value => value !== date)
}

function chooseFile(event: Event) {
  if (!(event.target instanceof HTMLInputElement))
    return
  file.value = event.target.files?.[0] ?? null
  event.target.value = ''
  if (file.value && !file.value.name.toLowerCase().endsWith('.xlsx')) {
    file.value = null
    error.value = t('请选择 .xlsx 文件')
  }
}

watch(importMode, () => {
  file.value = null
  downloadError.value = ''
})

async function downloadTemplate() {
  if (downloading.value)
    return
  const mode = importMode.value
  downloading.value = true
  downloadError.value = ''
  try {
    const workbook = await api.template(mode)
    if (workbook.type.includes('json'))
      throw new Error(t('模板下载失败'))
    const url = URL.createObjectURL(workbook)
    const link = document.createElement('a')
    link.href = url
    link.download = mode === 'SINGLE'
      ? 'trading-calendar-single.xlsx'
      : 'trading-calendar-multi.xlsx'
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  catch (failure) {
    if (mode === importMode.value)
      downloadError.value = calendarError(failure, '模板下载失败')
  }
  finally {
    downloading.value = false
  }
}

async function run(phase: Phase) {
  const operation = payload.value
  const token = phase === 'apply'
    ? fingerprint.value
    : undefined
  if (!operation || busy.value || (phase === 'apply' && !token))
    return

  running.value = phase
  result.value = null
  error.value = ''
  try {
    const confirmed = token || undefined
    const { data } = await (operation.kind === 'initialize'
      ? api.initialize(phase, { ...operation.data, fingerprint: confirmed })
      : operation.kind === 'batch'
        ? api.batch(phase, { ...operation.data, fingerprint: confirmed })
        : api.import(phase, { ...operation.data, fingerprint: confirmed }))
    if (phase === 'preview' && payload.value !== operation)
      return
    if (phase === 'preview' || data.errors.length) {
      result.value = data
    }
    else {
      window.$message?.success(t('交易日历已更新：新增 {0} 条，修改 {1} 条。', data.added, data.changed))
      emit('applied')
    }
  }
  catch (failure) {
    if (phase === 'apply' || payload.value === operation) {
      error.value = calendarError(failure, phase === 'preview'
        ? '预览失败'
        : '应用失败，请重新预览')
    }
  }
  finally {
    running.value = null
    if (previewPending) {
      previewPending = false
      void run('preview')
    }
  }
}
</script>

<style scoped>
.editor-form {
  display: grid;
  gap: 12px;
}

.date-field {
  display: grid;
  gap: 8px;
  width: 100%;
}

.date-actions,
.date-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.date-tags,
.validation-errors {
  max-height: 154px;
  overflow: auto;
}

.validation-errors {
  margin: 0;
  padding-inline-start: 1.5em;
}

.import-columns {
  margin: 0;
}

.file-name {
  overflow-wrap: anywhere;
}
</style>
