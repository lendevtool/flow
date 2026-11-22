<template>
  <n-form-item v-for="field in shown" :key="field.slot" :label="field.name">
    <n-select
      clearable
      :disabled="disabled || !field.enabled"
      :options="field.options.filter(option => option.enabled || option.id === model[field.slot]).map(option => ({
        value: option.id,
        label: option.name,
        disabled: !option.enabled,
      }))"
      :value="model[field.slot] ?? null"
      @update:value="value => setValue(field.slot, value)"
    />
  </n-form-item>
</template>

<script setup lang="ts">
import type { PublicDimension } from './api'
import { computed } from 'vue'

const props = defineProps<{ dimensions: PublicDimension[], disabled?: boolean }>()
const model = defineModel<Record<number, number>>({ required: true })
const shown = computed(() => props.dimensions.filter(field => field.enabled || model.value[field.slot] != null))

function setValue(slot: number, value: number | null) {
  const next = { ...model.value }
  if (value === null)
    delete next[slot]
  else
    next[slot] = value
  model.value = next
}
</script>
