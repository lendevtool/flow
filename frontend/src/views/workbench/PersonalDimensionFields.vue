<template>
  <n-form-item v-for="field in dimensions" :key="field.slot" :label="field.name">
    <n-select
      v-if="field.options.length"
      v-model:value="values[field.slot]"
      clearable
      :aria-label="field.name"
      :options="field.options.filter(option => option.enabled || option.key === original?.[field.slot]).map(option => ({
        label: option.name,
        value: option.key,
        disabled: !field.enabled || !option.enabled,
      }))"
    />
    <template v-else>
      <n-input
        :value="values[field.slot] ?? ''"
        :readonly="!field.enabled"
        :clearable="field.enabled"
        :maxlength="100"
        :input-props="{ 'aria-label': field.name }"
        @update:value="value => values[field.slot] = value"
      />
      <n-button v-if="!field.enabled && values[field.slot]" class="ml-8" text @click="values[field.slot] = null">
        {{ t('清除') }}
      </n-button>
    </template>
  </n-form-item>
</template>

<script setup lang="ts">
import type { PersonalDimension } from './api'
import { t } from '@/i18n'

defineProps<{ dimensions: PersonalDimension[], original?: Record<number, string> }>()
const values = defineModel<Record<number, string | null>>({ required: true })
</script>
