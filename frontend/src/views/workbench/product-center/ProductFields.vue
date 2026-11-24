<template>
  <n-form label-placement="left" label-align="left" :label-width="106" :disabled="disabled" @submit.prevent="emit('submit')">
    <div class="product-fields">
      <n-form-item v-for="field in fields" :key="field.key" :label="t(field.label)" :required="!field.optional">
        <n-input-number
          v-if="field.type === 'number'"
          :value="numberValue(values[field.key])"
          :min="0"
          :precision="field.integer ? 0 : undefined"
          :input-props="{ 'aria-label': t(field.label) }"
          class="w-full"
          @update:value="emit('change', field.key, $event)"
        />
        <n-date-picker
          v-else-if="field.type === 'date'"
          type="date"
          value-format="yyyy-MM-dd"
          :formatted-value="textValue(values[field.key])"
          :input-props="{ 'aria-label': t(field.label) }"
          class="w-full"
          @update:formatted-value="emit('change', field.key, textValue($event))"
        />
        <n-select
          v-else-if="field.type === 'select' || field.type === 'sku'"
          :aria-label="t(field.label)"
          :value="values[field.key]"
          :options="field.type === 'sku' ? [...skuOptions] : selectOptions(field)"
          :clearable="field.optional"
          :placeholder="field.type === 'sku' && !field.optional && !skuOptions.length ? t('请先新增销售规格') : undefined"
          @update:value="emit('change', field.key, $event)"
        />
        <n-input
          v-else
          :value="textValue(values[field.key])"
          :input-props="{ 'aria-label': t(field.label) }"
          :maxlength="200"
          @update:value="emit('change', field.key, $event)"
        />
      </n-form-item>
    </div>
    <slot />
  </n-form>
</template>

<script setup lang="ts">
import type { ProductField, ProductValues } from './model'
import { t } from '@/i18n'

withDefaults(defineProps<{
  fields: readonly ProductField[]
  values: ProductValues
  skuOptions?: readonly { label: string, value: number }[]
  disabled?: boolean
}>(), {
  skuOptions: () => [],
})

const emit = defineEmits<{
  change: [key: string, value: ProductValues[string]]
  submit: []
}>()

function textValue(value: unknown) {
  return typeof value === 'string'
    ? value
    : null
}

function numberValue(value: unknown) {
  return typeof value === 'number'
    ? value
    : null
}

function selectOptions(field: ProductField) {
  return Object.entries(field.options ?? {}).map(([value, label]) => ({
    value,
    label: t(label),
  }))
}
</script>

<style scoped>
.product-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 288px), 1fr));
  column-gap: 24px;
}
</style>
