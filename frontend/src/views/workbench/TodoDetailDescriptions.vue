<template>
  <n-descriptions :column="2" label-placement="top" bordered size="small">
    <slot name="before" />
    <n-descriptions-item :label="t('负责人')">
      {{ todo.assigneeName }}
    </n-descriptions-item>
    <n-descriptions-item :label="t('计划日期')">
      {{ todo.startDate ? `${todo.startDate} ~ ${todo.endDate}` : '—' }}
    </n-descriptions-item>
    <n-descriptions-item :label="t('截止日期')">
      {{ todo.dueDate ?? '—' }}
    </n-descriptions-item>
    <slot />
  </n-descriptions>
</template>

<script setup lang="ts">
import type { ScheduleDates } from './api'
import { t } from '@/i18n'

defineProps<{ todo: ScheduleDates & { assigneeName: string | null } }>()
</script>
