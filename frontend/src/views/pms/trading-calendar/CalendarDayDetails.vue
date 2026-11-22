<template>
  <n-card size="small">
    <template #header>
      <span v-if="marketIcon(market)" class="mr-4" aria-hidden="true">{{ marketIcon(market) }}</span>
      {{ marketName(market) }}
    </template>
    <template #header-extra>
      <n-tag :type="day ? (day.isTradingDay ? 'success' : 'warning') : 'default'" size="small">
        {{ dayStatus(day) }}
      </n-tag>
    </template>
    <dl class="details">
      <dt>{{ t('日期') }}</dt>
      <dd>{{ date }}</dd>
      <dt>{{ t('节假日') }}</dt>
      <dd>{{ day?.holidayName || '—' }}</dd>
      <dt>{{ t('备注') }}</dt>
      <dd>{{ day?.remark || '—' }}</dd>
    </dl>
  </n-card>
</template>

<script setup lang="ts">
import type { CalendarDay, CalendarMarket } from './api'
import { t } from '@/i18n'
import { dayStatus, marketIcon, marketName } from './model'

defineProps<{ market: CalendarMarket, date: string, day?: CalendarDay }>()
</script>

<style scoped>
.details {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  gap: 4px 16px;
  margin: 0;
}

.details dd {
  margin: 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
</style>
