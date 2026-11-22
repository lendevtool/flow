<template>
  <n-input-number
    v-if="field.type === 'number'" :value="numberValue" :disabled="disabled"
    class="w-full" :placeholder="t('请输入{0}', field.label)" @update:value="update"
  />
  <n-select
    v-else-if="field.type === 'boolean'" :value="booleanValue" :disabled="disabled"
    :options="booleanOptions" :placeholder="t('请选择')" clearable @update:value="value => update(value === null ? null : value === 'true')"
  />
  <n-select
    v-else-if="field.type === 'select'" :value="value" :disabled="disabled"
    :options="field.options || []" :placeholder="t('请选择{0}', field.label)" clearable @update:value="update"
  />
  <n-input
    v-else :value="value ?? ''" :disabled="disabled" :maxlength="field.storage === 'fixed' ? 512 : 4096"
    :placeholder="t('请输入{0}', field.label)" clearable @update:value="update"
  />
</template>

<script setup>
import { t } from '@/i18n'

const props = defineProps({
  field: { type: Object, required: true },
  value: { default: null },
  disabled: Boolean,
})
const emit = defineEmits(['update:value'])
const numberValue = computed(() => props.value === '' || props.value === null || props.value === undefined
  ? null
  : Number(props.value))
const booleanValue = computed(() => props.value === null || props.value === undefined
  ? null
  : String(props.value))
const booleanOptions = computed(() => [
  {
    label: t('是'),
    value: 'true',
  },
  {
    label: t('否'),
    value: 'false',
  },
])
function update(value) {
  emit('update:value', value)
}
</script>
