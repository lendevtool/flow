<template>
  <CommonPage :title="t('交易日历')">
    <div class="calendar-toolbar">
      <div class="toolbar-group year-picker" role="group" :aria-label="t('年份')">
        <n-button secondary size="small" :disabled="year <= minYear" :aria-label="t('上一年')" @click="year--">
          <template #icon>
            <i class="i-mdi:chevron-left" aria-hidden="true" />
          </template>
        </n-button>
        <n-date-picker
          :value="yearTimestamp"
          type="year"
          size="small"
          :is-date-disabled="yearDisabled"
          @update:value="pickYear"
        />
        <n-button secondary size="small" :disabled="year >= maxYear" :aria-label="t('下一年')" @click="year++">
          <template #icon>
            <i class="i-mdi:chevron-right" aria-hidden="true" />
          </template>
        </n-button>
        <n-button text size="small" :disabled="year === currentYear" @click="year = currentYear">
          {{ t('今年') }}
        </n-button>
      </div>
      <div class="toolbar-group">
        <n-button size="small" :disabled="!markets.length" @click="lookup = true">
          {{ t('交易日查询') }}
        </n-button>
        <n-button v-if="can('TradingCalendarInit')" size="small" :disabled="!markets.length" @click="openManagement('initialize')">
          {{ t('初始化全年') }}
        </n-button>
        <n-button v-if="can('TradingCalendarEdit')" size="small" :disabled="!markets.length" @click="openManagement('batch')">
          {{ t('批量编辑') }}
        </n-button>
        <n-button v-if="can('TradingCalendarImport')" type="primary" size="small" :disabled="!markets.length" @click="openManagement('import')">
          {{ t('Excel 导入') }}
        </n-button>
      </div>
    </div>

    <n-spin :show="loadingMarkets">
      <n-alert v-if="marketError" type="error" class="mb-12">
        {{ marketError }}
        <n-button text @click="loadMarkets">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <div v-else-if="markets.length" class="market-toolbar">
        <div class="toolbar-group" role="group" :aria-label="t('市场筛选')">
          <span class="muted">{{ t('市场筛选') }}</span>
          <n-button
            v-for="market in markets"
            :key="market.code"
            size="small"
            round
            :type="marketCodes.includes(market.code) ? 'primary' : 'default'"
            :secondary="marketCodes.includes(market.code)"
            :aria-pressed="marketCodes.includes(market.code)"
            :disabled="marketCodes.length === 1 && marketCodes[0] === market.code"
            @click="toggleMarket(market.code)"
          >
            <span v-if="marketIcon(market)" class="market-icon" aria-hidden="true">{{ marketIcon(market) }}</span>
            {{ marketName(market) }}
          </n-button>
        </div>
        <div class="toolbar-group legend" :aria-label="t('图例')">
          <span><i class="legend-mark trading" />{{ t('交易') }}</span>
          <span><i class="legend-mark closed" />{{ t('休市') }}</span>
          <span><i class="legend-mark missing" />{{ t('无日历数据') }}</span>
        </div>
      </div>
      <n-empty v-else-if="!loadingMarkets" :description="t('没有可用市场')" />
    </n-spin>

    <template v-if="markets.length">
      <div v-if="can('TradingCalendarEdit')" class="selection-toolbar">
        <n-button size="small" :type="selecting ? 'primary' : 'default'" secondary :aria-pressed="selecting" @click="toggleSelection">
          {{ t(selecting ? '退出日期选择' : '选择多个日期') }}
        </n-button>
        <template v-if="selecting">
          <span aria-live="polite">{{ t('已选 {0} 个日期', selectedDates.length) }}</span>
          <n-button text size="small" :disabled="!selectedDates.length" @click="selectedDates = []">
            {{ t('清空选择') }}
          </n-button>
          <n-button type="primary" size="small" :disabled="!selectedDates.length" @click="openManagement('batch', selectedDates)">
            {{ t('编辑所选日期') }}
          </n-button>
        </template>
      </div>

      <n-alert v-if="yearError" type="error" class="mb-12">
        {{ yearError }}
        <n-button text @click="loadYear">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-spin :show="loadingYear">
        <div v-if="!yearError && !loadingYear" class="calendar-grid">
          <n-card v-for="month in months" :key="month.month" size="small" class="month-card" :title="monthName(month.month)">
            <div class="month-grid">
              <span v-for="(weekday, index) in weekdays" :key="weekday" class="weekday" :class="{ weekend: index > 4 }">
                {{ t(weekday) }}
              </span>
              <template v-for="(date, index) in month.days" :key="date ?? index">
                <span v-if="!date" class="empty-cell" />
                <button
                  v-else
                  type="button"
                  class="day"
                  :class="{ today: date === today, selected: selectedDateSet.has(date) }"
                  :aria-label="cellLabel(date)"
                  :aria-current="date === today ? 'date' : undefined"
                  :aria-pressed="selecting ? selectedDateSet.has(date) : undefined"
                  @click="clickDate(date)"
                >
                  <span class="day-number">{{ Number(date.slice(-2)) }}</span>
                  <span class="day-markets" aria-hidden="true">
                    <span
                      v-for="market in shownMarkets"
                      :key="market.code"
                      class="day-mark"
                      :class="dayClass(calendar.get(date)?.get(market.code))"
                      :title="statusDescription(date, market)"
                    >{{ market.code }}</span>
                  </span>
                </button>
              </template>
            </div>
          </n-card>
        </div>
      </n-spin>
    </template>

    <n-modal v-model:show="details" preset="card" :title="t('日期详情')" style="width: min(540px, 94vw)">
      <div v-if="activeDate" class="day-details">
        <CalendarDayDetails
          v-for="market in shownMarkets"
          :key="market.code"
          :market="market"
          :date="activeDate"
          :day="calendar.get(activeDate)?.get(market.code)"
        />
      </div>
      <template v-if="can('TradingCalendarEdit')" #footer>
        <n-space justify="end">
          <n-button type="primary" @click="editActiveDate">
            {{ t('编辑日期') }}
          </n-button>
        </n-space>
      </template>
    </n-modal>

    <CalendarManagement
      v-if="management"
      :kind="management.kind"
      :markets="markets"
      :market-codes="marketCodes"
      :year="year"
      :dates="management.dates"
      :initial-status="management.initialStatus"
      @close="management = null"
      @applied="afterApply"
    />

    <CalendarLookup
      v-if="lookup"
      :markets="markets"
      :market-code="marketCodes[0]"
      :date="activeDate || today"
      @close="lookup = false"
    />
  </CommonPage>
</template>

<script setup lang="ts">
import type { CalendarDay, CalendarMarket } from './api'
import type { ManagementKind } from './model'
import dayjs from 'dayjs'
import { useThemeVars } from 'naive-ui'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { language, t } from '@/i18n'
import { useUserStore } from '@/store'
import api from './api'
import CalendarDayDetails from './CalendarDayDetails.vue'
import CalendarLookup from './CalendarLookup.vue'
import CalendarManagement from './CalendarManagement.vue'
import { buildYear, calendarError, dayClass, daysByDate, dayStatus, marketIcon, marketName, maxYear, minYear } from './model'

defineOptions({ name: 'TradingCalendarMgt' })

const route = useRoute()
const user = useUserStore()
const theme = useThemeVars()
const today = dayjs().format('YYYY-MM-DD')
const currentYear = Number(today.slice(0, 4))
const year = ref(currentYear)
const yearTimestamp = computed(() => new Date(year.value, 0, 1).getTime())
const months = computed(() => buildYear(year.value))
const monthFormatter = computed(() => new Intl.DateTimeFormat(language.value, { month: 'long', timeZone: 'UTC' }))
const weekdays = ['一', '二', '三', '四', '五', '六', '日']
const markets = ref<CalendarMarket[]>([])
const marketCodes = ref<string[]>([])
const shownMarkets = computed(() => markets.value.filter(market => marketCodes.value.includes(market.code)))
const records = ref<CalendarDay[]>([])
const calendar = computed(() => daysByDate(records.value))
const loadingMarkets = ref(false)
const loadingYear = ref(false)
const marketError = ref('')
const yearError = ref('')
const activeDate = ref<string | null>(null)
const details = ref(false)
const selecting = ref(false)
const selectedDates = ref<string[]>([])
const selectedDateSet = computed(() => new Set(selectedDates.value))
const management = ref<{ kind: ManagementKind, dates: string[], initialStatus?: boolean } | null>(null)
const lookup = ref(false)
let yearRequest = 0

function can(code: string) {
  const buttons = route.meta.btns
  return user.currentRole.code === 'SUPER_ADMIN'
    || (Array.isArray(buttons) && buttons.some(button => button.code === code))
}

function pickYear(timestamp: number | null) {
  if (timestamp !== null && !yearDisabled(timestamp))
    year.value = new Date(timestamp).getFullYear()
}

function yearDisabled(timestamp: number) {
  const value = new Date(timestamp).getFullYear()
  return value < minYear || value > maxYear
}

function monthName(month: number) {
  return monthFormatter.value.format(new Date(Date.UTC(year.value, month, 1)))
}

function toggleMarket(code: string) {
  if (!marketCodes.value.includes(code)) {
    marketCodes.value = [...marketCodes.value, code]
  }
  else if (marketCodes.value.length > 1) {
    marketCodes.value = marketCodes.value.filter(value => value !== code)
  }
}

async function loadMarkets() {
  loadingMarkets.value = true
  marketError.value = ''
  try {
    const { data } = await api.markets()
    markets.value = data
    const defaultMarket = data.find(market => market.code === 'CN') ?? data[0]
    marketCodes.value = defaultMarket
      ? [defaultMarket.code]
      : []
  }
  catch (error) {
    marketError.value = calendarError(error, '市场加载失败')
  }
  finally {
    loadingMarkets.value = false
  }
}

async function loadYear() {
  const requestId = ++yearRequest
  records.value = []
  yearError.value = ''
  if (!marketCodes.value.length) {
    loadingYear.value = false
    return
  }
  loadingYear.value = true
  try {
    const { data } = await api.year(year.value, marketCodes.value)
    if (requestId === yearRequest)
      records.value = data
  }
  catch (error) {
    if (requestId === yearRequest)
      yearError.value = calendarError(error, '交易日历加载失败')
  }
  finally {
    if (requestId === yearRequest)
      loadingYear.value = false
  }
}

function statusDescription(date: string, market: CalendarMarket) {
  const day = calendar.value.get(date)?.get(market.code)
  return [
    marketName(market),
    dayStatus(day),
    day?.holidayName,
  ].filter(Boolean).join(' · ')
}

function cellLabel(date: string) {
  return [
    date,
    ...shownMarkets.value.map(market => statusDescription(date, market)),
  ].join('; ')
}

function toggleSelection() {
  selecting.value = !selecting.value
  selectedDates.value = []
}

function clickDate(date: string) {
  if (selecting.value) {
    selectedDates.value = selectedDateSet.value.has(date)
      ? selectedDates.value.filter(value => value !== date)
      : [...selectedDates.value, date].sort()
  }
  else {
    activeDate.value = date
    details.value = true
  }
}

function openManagement(kind: ManagementKind, dates: string[] = [], initialStatus?: boolean) {
  management.value = { kind, dates, initialStatus }
}

function editActiveDate() {
  if (!activeDate.value)
    return
  details.value = false
  const status = shownMarkets.value.length === 1
    ? calendar.value.get(activeDate.value)?.get(shownMarkets.value[0].code)?.isTradingDay
    : undefined
  openManagement('batch', [activeDate.value], status)
}

function afterApply() {
  management.value = null
  selecting.value = false
  selectedDates.value = []
  void loadYear()
}

onMounted(loadMarkets)
watch([year, marketCodes], loadYear)
watch(year, () => {
  selectedDates.value = []
  activeDate.value = null
})
</script>

<style scoped>
.calendar-toolbar,
.market-toolbar,
.selection-toolbar,
.toolbar-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.calendar-toolbar,
.market-toolbar {
  justify-content: space-between;
}

.calendar-toolbar,
.market-toolbar,
.selection-toolbar {
  margin-bottom: 14px;
}

.year-picker .n-date-picker {
  width: 100px;
}

.market-icon {
  margin-inline-end: 5px;
}

.legend {
  color: v-bind('theme.textColor2');
  font-size: 12px;
}

.legend > span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.legend-mark {
  width: 9px;
  height: 9px;
  border: 1px solid currentColor;
  border-radius: 3px;
}

.muted,
.weekday {
  color: v-bind('theme.textColor3');
}

.calendar-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 264px), 1fr));
  gap: 14px;
}

.month-card {
  min-width: 0;
}

.month-card :deep(.n-card-header) {
  padding-bottom: 7px;
}

.month-card :deep(.n-card__content) {
  padding-inline: 11px;
}

.month-grid {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  align-content: start;
  gap: 3px;
}

.weekday {
  text-align: center;
  font-size: 11px;
  padding-block: 0 5px;
}

.weekend {
  color: v-bind('theme.warningColor');
}

.day,
.empty-cell {
  min-width: 0;
  min-height: 36px;
  border: 1px solid transparent;
  border-radius: 5px;
}

.day {
  color: v-bind('theme.textColor1');
  background: v-bind('theme.actionColor');
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 3px 1px;
  cursor: pointer;
}

.day:hover,
.day:focus-visible,
.day.today {
  border-color: v-bind('theme.primaryColor');
}

.day.selected {
  border-color: v-bind('theme.primaryColor');
  box-shadow: inset 0 0 0 1px v-bind('theme.primaryColor');
}

.day-number {
  font-variant-numeric: tabular-nums;
  font-size: 12px;
  line-height: 1.3;
}

.day.today .day-number {
  color: v-bind('theme.primaryColor');
  font-weight: 700;
}

.day-markets {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 2px;
  margin-top: 3px;
}

.day-mark {
  font-size: 9px;
  font-weight: 650;
  line-height: 11px;
  padding-inline: 2px;
  border: 1px solid transparent;
  border-radius: 3px;
}

.trading {
  color: v-bind('theme.successColor');
  background: color-mix(in srgb, currentColor 14%, transparent);
}

.closed {
  color: v-bind('theme.warningColor');
  background: color-mix(in srgb, currentColor 14%, transparent);
}

.missing {
  color: v-bind('theme.textColor3');
  border-color: v-bind('theme.borderColor');
  border-style: dashed;
}

.day-details {
  display: grid;
  gap: 10px;
}

@media (max-width: 520px) {
  .calendar-toolbar,
  .market-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
