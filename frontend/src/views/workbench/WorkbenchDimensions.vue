<template>
  <n-collapse v-if="fields.length">
    <n-collapse-item :title="t('更多维度')" name="dimensions">
      <n-form inline class="flex-wrap" :show-feedback="false">
        <n-form-item v-for="field in fields" :key="field.key" :label="field.label">
          <n-select
            class="min-w-180"
            clearable
            multiple
            :filterable="field.text"
            :tag="field.text"
            :placeholder="field.text ? t('输入文本后按回车') : undefined"
            :value="model[field.key] ?? []"
            :options="field.options"
            @update:value="values => setValues(field.key, values)"
          />
        </n-form-item>
      </n-form>
    </n-collapse-item>
  </n-collapse>
</template>

<script setup lang="ts">
import type { PersonalDimension, PublicDimension } from './api'
import type { DimensionFilter } from './model'
import { computed } from 'vue'
import { t } from '@/i18n'

const props = defineProps<{ publicDimensions: PublicDimension[], personalDimensions: PersonalDimension[] }>()
const model = defineModel<DimensionFilter>({ required: true })

const fields = computed(() => [
  ...props.publicDimensions.filter(field => field.options.length).map(field => ({
    key: `public${field.slot}` as const,
    label: `${t('公共维度')} · ${field.name}`,
    text: false,
    options: field.options.map(option => ({
      label: option.name,
      value: String(option.id),
    })),
  })),
  ...props.personalDimensions.map(field => ({
    key: `personal${field.slot}` as const,
    label: `${t('我的个人维度')} · ${field.name}`,
    text: !field.options.length,
    options: field.options.map(option => ({ label: option.name, value: option.key })),
  })),
])

function setValues(key: keyof DimensionFilter, values: string[]) {
  const next = { ...model.value }
  const selected = values.map(value => value.trim()).filter(Boolean)
  if (selected.length)
    next[key] = selected
  else
    delete next[key]
  model.value = next
}
</script>
