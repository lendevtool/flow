<template>
  <div class="date-range-filter" :class="{ 'date-range-filter-labeled': label }" role="group" :aria-label="label ?? t('时间段')">
    <strong v-if="label" class="date-range-label">{{ label }}</strong>
    <div class="date-range-inputs">
      <n-form-item :label="t('开始日期')" label-placement="left" :show-feedback="false">
        <n-date-picker
          :formatted-value="range?.[0] ?? null"
          class="w-full"
          type="date"
          value-format="yyyy-MM-dd"
          clearable
          @update:formatted-value="updateDate(0, $event)"
        />
      </n-form-item>
      <n-form-item :label="t('结束日期')" label-placement="left" :show-feedback="false">
        <n-date-picker
          :formatted-value="range?.[1] ?? null"
          class="w-full"
          type="date"
          value-format="yyyy-MM-dd"
          :status="reversed ? 'error' : undefined"
          clearable
          @update:formatted-value="updateDate(1, $event)"
        />
      </n-form-item>
      <n-space class="date-range-presets" :size="6">
        <n-button
          v-for="(name, key) in presets"
          :key="key"
          size="tiny"
          :type="selectedPreset === key ? 'primary' : 'default'"
          :aria-pressed="selectedPreset === key"
          secondary
          @click="applyPreset(key)"
        >
          {{ t(name) }}
        </n-button>
      </n-space>
      <n-text v-if="reversed" type="warning" role="alert" class="date-range-error">
        {{ t('结束日期不能早于开始日期。') }}
      </n-text>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { DateRange, Preset } from '@/utils/businessDate'
import { computed, ref } from 'vue'
import { t } from '@/i18n'
import { datePreset, formatBusinessToday, isDateRangeReversed } from '@/utils/businessDate'

defineProps<{ label?: string }>()
const range = defineModel<DateRange>({ required: true })
const presets: Record<Preset, string> = {
  today: '今天',
  week: '本周',
  month: '本月',
  past: '最近 7 天',
  future: '未来 7 天',
  any: '不限',
}
const applied = ref<{ key: Preset, dates: NonNullable<DateRange> }>()
const reversed = computed(() => isDateRangeReversed(range.value))
const selectedPreset = computed(() => {
  const [from, to] = range.value ?? []
  if (!from && !to)
    return 'any'
  const choice = applied.value
  return choice && from === choice.dates[0] && to === choice.dates[1]
    ? choice.key
    : null
})

function applyPreset(key: Preset) {
  const dates = datePreset(key, formatBusinessToday())
  applied.value = { key, dates }
  range.value = key === 'any'
    ? null
    : dates
}

function updateDate(index: 0 | 1, value: string | null) {
  const dates: NonNullable<DateRange> = [range.value?.[0] ?? null, range.value?.[1] ?? null]
  dates[index] = value
  applied.value = undefined
  range.value = dates[0] || dates[1]
    ? dates
    : null
}
</script>

<style scoped>
.date-range-filter {
  min-width: 0;
  container-type: inline-size;
}
.date-range-filter-labeled {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: start;
  column-gap: 12px;
}
.date-range-inputs {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
  gap: 8px 12px;
}
.date-range-presets,
.date-range-error {
  grid-column: 1 / -1;
}
.date-range-label {
  display: flex;
  align-items: center;
  min-height: 34px;
  font-weight: 500;
  white-space: nowrap;
}
@container (min-width: 430px) {
  .date-range-inputs {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
