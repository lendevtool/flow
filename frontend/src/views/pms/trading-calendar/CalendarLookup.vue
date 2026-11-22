<template>
  <n-modal :show="true" preset="card" :title="t('交易日查询')" style="width: min(500px, 94vw)" @update:show="emit('close')">
    <n-form :show-feedback="false" class="lookup-form">
      <n-radio-group v-model:value="mode">
        <n-space>
          <n-radio value="STATUS">
            {{ t('日期状态') }}
          </n-radio>
          <n-radio value="OFFSET">
            {{ t('交易日偏移（T+n）') }}
          </n-radio>
        </n-space>
      </n-radio-group>
      <n-form-item :label="t('市场')" required>
        <n-select v-model:value="selectedCode" :options="marketOptions" />
      </n-form-item>
      <n-form-item :label="t(mode === 'STATUS' ? '日期' : '基准日期')" required>
        <n-date-picker
          :formatted-value="selectedDate"
          value-format="yyyy-MM-dd"
          clearable
          class="w-full"
          @update:formatted-value="setDate"
        />
      </n-form-item>
      <template v-if="mode === 'OFFSET'">
        <n-form-item :label="t('偏移交易日数')" required>
          <n-input v-model:value="offset" :placeholder="t('例如 -2、0、+3')" @keydown.enter.prevent="query" />
        </n-form-item>
        <n-alert v-if="invalidOffset" type="error">
          {{ t('请输入有效的整数偏移量') }}
        </n-alert>
      </template>
      <n-alert v-if="error" type="error" role="alert">
        {{ error }}
      </n-alert>
      <CalendarDayDetails
        v-if="statusResult && selectedMarket"
        :market="selectedMarket"
        :date="statusResult.calendarDate"
        :day="statusResult"
      />
      <n-alert v-if="offsetResult && selectedMarket" type="success" :title="t('查询结果')" aria-live="polite">
        {{ marketName(selectedMarket) }} · {{ offsetResult }}
      </n-alert>
    </n-form>
    <template #footer>
      <n-space justify="end">
        <n-button @click="emit('close')">
          {{ t('关闭') }}
        </n-button>
        <n-button type="primary" :loading="loading" :disabled="!canQuery || loading" @click="query">
          {{ t('查询') }}
        </n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import type { CalendarDay, CalendarMarket } from './api'
import { computed, ref, watch } from 'vue'
import { t } from '@/i18n'
import api from './api'
import CalendarDayDetails from './CalendarDayDetails.vue'
import { calendarError, marketName, parseTradingOffset } from './model'

const props = defineProps<{ markets: CalendarMarket[], marketCode?: string, date: string }>()
const emit = defineEmits<{ close: [] }>()
const mode = ref<'STATUS' | 'OFFSET'>('STATUS')
const selectedCode = ref(props.marketCode ?? props.markets[0]?.code ?? null)
const selectedDate = ref<string | null>(props.date)
const offset = ref('1')
const parsedOffset = computed(() => parseTradingOffset(offset.value))
const invalidOffset = computed(() => !!offset.value && parsedOffset.value === null)
const canQuery = computed(() => !!selectedCode.value && !!selectedDate.value && (mode.value === 'STATUS' || parsedOffset.value !== null))
const loading = ref(false)
const error = ref('')
const statusResult = ref<CalendarDay | null>(null)
const offsetResult = ref<string | null>(null)
const selectedMarket = computed(() => props.markets.find(market => market.code === selectedCode.value))
const marketOptions = computed(() => props.markets.map(market => ({
  label: `${marketName(market)} (${market.code})`,
  value: market.code,
})))
let request = 0

watch([
  mode,
  selectedCode,
  selectedDate,
  offset,
], () => {
  request++
  loading.value = false
  clearResults()
}, { flush: 'sync' })

function clearResults() {
  error.value = ''
  statusResult.value = null
  offsetResult.value = null
}

function setDate(value: string | [string, string] | null) {
  selectedDate.value = typeof value === 'string'
    ? value
    : null
}

async function query() {
  const code = selectedCode.value
  const date = selectedDate.value
  const n = parsedOffset.value
  if (!canQuery.value || !code || !date || loading.value)
    return
  const requestId = ++request
  loading.value = true
  clearResults()
  try {
    if (mode.value === 'STATUS') {
      const { data } = await api.status(code, date)
      if (requestId === request)
        statusResult.value = data
    }
    else if (n !== null) {
      const { data } = await api.offset(code, date, n)
      if (requestId === request)
        offsetResult.value = data.date
    }
  }
  catch (failure) {
    if (requestId === request)
      error.value = calendarError(failure, '查询失败')
  }
  finally {
    if (requestId === request)
      loading.value = false
  }
}
</script>

<style scoped>
.lookup-form {
  display: grid;
  gap: 12px;
}
</style>
