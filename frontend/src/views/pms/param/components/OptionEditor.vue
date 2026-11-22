<template>
  <n-form-item :label="t('选项（名称 / 存储值）')">
    <n-dynamic-input :value="value" :on-create="() => ({ label: '', value: '' })" :max="100" @update:value="emit('update:value', $event)">
      <template #default="{ value: option }">
        <div class="flex gap-8">
          <n-input v-model:value="option.label" :placeholder="t('显示名称')" :maxlength="100" />
          <n-input v-model:value="option.value" :placeholder="t('存储值')" :maxlength="512" />
        </div>
      </template>
    </n-dynamic-input>
  </n-form-item>
</template>

<script setup>
import { t } from '@/i18n'

defineProps({
  value: {
    type: Array,
    default: () => [],
  },
})
const emit = defineEmits(['update:value'])
</script>
