<template>
  <n-space justify="space-between" align="center" class="mb-12">
    <n-space align="center">
      <n-button-group>
        <n-button :aria-label="t('上一周期')" @click="emit('update:cursor', shiftPeriod(cursor, period, -1))">
          ‹
        </n-button>
        <n-button :aria-label="t('下一周期')" @click="emit('update:cursor', shiftPeriod(cursor, period, 1))">
          ›
        </n-button>
      </n-button-group>
      <n-button @click="emit('update:cursor', formatBusinessToday())">
        {{ t('今天') }}
      </n-button>
      <strong class="text-18">{{ heading }}</strong>
    </n-space>
    <n-radio-group :value="period" @update:value="setPeriod">
      <n-radio-button value="week">
        {{ t('周') }}
      </n-radio-button>
      <n-radio-button value="month">
        {{ t('月') }}
      </n-radio-button>
    </n-radio-group>
  </n-space>
</template>

<script setup lang="ts">
import type { Period } from './model'
import { computed } from 'vue'
import { language, t } from '@/i18n'
import { calendarDays, formatBusinessToday, shiftPeriod } from './model'

const props = defineProps<{ cursor: string, period: Period }>()
const emit = defineEmits<{ 'update:cursor': [date: string], 'update:period': [period: Period] }>()
const days = computed(() => calendarDays(props.cursor, props.period))
const heading = computed(() => props.period === 'week'
  ? `${days.value[0]} ~ ${days.value.at(-1)}`
  : new Intl.DateTimeFormat(language.value, { year: 'numeric', month: 'long', timeZone: 'UTC' }).format(new Date(`${props.cursor}T00:00:00Z`)))

function setPeriod(value: string | number | boolean) {
  if (value === 'week' || value === 'month')
    emit('update:period', value)
}
</script>
