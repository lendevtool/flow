<template>
  <n-spin :show="loading">
    <section class="calendar-scroll" :aria-label="t('待办日历')">
      <div class="calendar">
        <div v-if="period === 'month'" class="calendar-week calendar-head">
          <span v-for="weekday in weekdays" :key="weekday">{{ t(weekday) }}</span>
        </div>
        <div v-for="week in weeks" :key="week.days[0]" class="calendar-week" :class="{ 'calendar-week-roomy': period === 'week' }">
          <div
            v-for="(date, index) in week.days"
            :key="date"
            class="day-background"
            :class="{ 'other-month': period === 'month' && date.slice(0, 7) !== cursor.slice(0, 7) }"
            :style="{ gridColumn: index + 1 }"
            aria-hidden="true"
          />
          <div v-for="(date, index) in week.days" :key="`day-${date}`" class="day-heading" :style="{ gridColumn: index + 1 }">
            <span v-if="period === 'week'">{{ t(weekdays[index]) }}</span>
            <n-button
              text
              :type="date === today ? 'primary' : 'default'"
              :aria-current="date === today ? 'date' : undefined"
              :aria-label="dateLabel?.(date) ?? date"
              @click="emit('selectDate', date)"
            >
              {{ date.slice(-2) }}
            </n-button>
          </div>
          <button
            v-for="item in week.items"
            :key="item.todo.id"
            type="button"
            class="calendar-item"
            :class="{ 'due-item': item.dueOnly, 'finished-item': item.todo.status === 'DONE' }"
            :title="item.todo.title"
            :style="{ gridColumn: `${item.column + 1} / span ${item.span}`, gridRow: item.lane + 2 }"
            @click="emit('openItem', item.todo)"
          >
            <slot name="item" :todo="item.todo" :due-only="item.dueOnly">
              {{ item.dueOnly ? '◆ ' : '' }}{{ item.todo.title }}
            </slot>
          </button>
          <button
            v-for="item in week.overflow"
            :key="item.date"
            type="button"
            class="calendar-more"
            :style="{ gridColumn: item.column + 1, gridRow: 5 }"
            @click="emit('openDay', item.date)"
          >
            {{ t('+{0} 项', item.count) }}
          </button>
        </div>
      </div>
    </section>
  </n-spin>
  <n-space class="mt-12">
    <n-tag type="info" :bordered="false">
      {{ t('计划日期') }}
    </n-tag>
    <n-tag type="warning" :bordered="false">
      ◆ {{ t('仅截止日期') }}
    </n-tag>
  </n-space>
</template>

<script setup lang="ts" generic="Item extends ScheduleDates & { id: number | string, title: string, status: string }">
import type { ScheduleDates } from './api'
import type { Period } from './model'
import { useThemeVars } from 'naive-ui'
import { computed } from 'vue'
import { t } from '@/i18n'
import { layoutCalendarWeek } from './calendarLayout'
import { calendarDays, formatBusinessToday } from './model'

const props = defineProps<{
  cursor: string
  period: Period
  items: Item[]
  loading: boolean
  dateLabel?: (date: string) => string
}>()
const emit = defineEmits<{ openItem: [todo: Item], selectDate: [date: string], openDay: [date: string] }>()

const theme = useThemeVars()
const today = formatBusinessToday()
const weekdays = [
  '周一',
  '周二',
  '周三',
  '周四',
  '周五',
  '周六',
  '周日',
]
const weeks = computed(() => {
  const days = calendarDays(props.cursor, props.period)
  const weeks = []
  for (let index = 0; index < days.length; index += 7)
    weeks.push(layoutCalendarWeek(days.slice(index, index + 7), props.items, props.period))
  return weeks
})
</script>

<style scoped>
.calendar-scroll {
  overflow-x: auto;
  border: 1px solid v-bind('theme.borderColor');
  border-radius: 8px;
}

.calendar {
  min-width: 650px;
}

.calendar-week {
  display: grid;
  position: relative;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  grid-auto-rows: minmax(24px, auto);
  align-content: start;
  min-height: 126px;
  padding-bottom: 6px;
  border-bottom: 1px solid v-bind('theme.borderColor');
}

.calendar-week:last-child {
  border-bottom: 0;
}

.calendar-week-roomy {
  min-height: 190px;
}

.calendar-head {
  min-height: 0;
  padding: 8px 0;
  text-align: center;
  background: v-bind('theme.tableHeaderColor');
}

.day-background {
  position: absolute;
  inset: 0;
  border-right: 1px solid v-bind('theme.borderColor');
  pointer-events: none;
}

.day-background:last-of-type {
  border-right: 0;
}

.other-month {
  background: v-bind('theme.tableHeaderColor');
  opacity: 0.75;
}

.day-heading {
  z-index: 1;
  grid-row: 1;
  padding: 4px 8px;
}

.calendar-week-roomy .day-heading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding-block: 8px;
  background: v-bind('theme.tableHeaderColor');
}

.calendar-item,
.calendar-more {
  z-index: 1;
  margin: 1px 4px;
  padding: 2px 6px;
  overflow: hidden;
  cursor: pointer;
  white-space: nowrap;
  text-overflow: ellipsis;
  text-align: left;
  border: 0;
  border-radius: 4px;
}

.calendar-item {
  background: color-mix(in srgb, v-bind('theme.infoColor') 14%, v-bind('theme.cardColor'));
  color: v-bind('theme.textColor1');
}

.due-item {
  background: color-mix(in srgb, v-bind('theme.warningColor') 20%, v-bind('theme.cardColor'));
}

.finished-item {
  opacity: 0.7;
}

.calendar-more {
  background: transparent;
  color: v-bind('theme.primaryColor');
}
</style>
