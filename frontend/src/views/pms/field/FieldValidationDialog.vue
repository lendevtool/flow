<template>
  <n-modal
    :show="true"
    preset="card"
    :title="t('校验测试')"
    closable
    style="width: min(520px, calc(100vw - 32px))"
    @update:show="!$event && emit('close')"
  >
    <n-text depth="3">
      {{ displayName(field.config.name, field.config.nameEn) }} ({{ field.config.code }}) · {{ typeLabel }}
    </n-text>
    <n-form class="mt-16" @submit.prevent="submit">
      <n-checkbox v-model:checked="useNull" :disabled="loading" class="mb-12">
        {{ t('传入空值（null）') }}
      </n-checkbox>
      <n-form-item :label="t('测试值')" label-placement="top">
        <n-radio-group v-if="type === 'BOOLEAN'" v-model:value="input" :disabled="loading || useNull" :aria-label="t('测试值')">
          <n-space>
            <n-radio value="true">
              true
            </n-radio>
            <n-radio value="false">
              false
            </n-radio>
          </n-space>
        </n-radio-group>
        <n-input
          v-else
          v-model:value="input"
          :type="type === 'TEXT' || type === 'ENUM_MULTI' || type === 'ATTACHMENT' ? 'textarea' : 'text'"
          :placeholder="placeholder"
          :disabled="loading || useNull"
          :input-props="{ 'aria-label': t('测试值'), 'spellcheck': false }"
        />
      </n-form-item>
      <div
        ref="resultArea"
        class="validation-result mb-16"
        aria-live="polite"
        :aria-busy="loading"
        :tabindex="outcome?.message ? 0 : undefined"
      >
        <n-alert v-if="outcome" :type="outcome.type" :title="outcome.title">
          <span v-if="outcome.message" class="validation-message">{{ outcome.message }}</span>
        </n-alert>
      </div>
      <n-space justify="end">
        <n-button @click="emit('close')">
          {{ t('关闭') }}
        </n-button>
        <n-button type="primary" attr-type="submit" :loading="loading">
          <template #icon>
            <i class="i-fe:check-circle" aria-hidden="true" />
          </template>
          {{ t('校验') }}
        </n-button>
      </n-space>
    </n-form>
  </n-modal>
</template>

<script setup lang="ts">
import type { FieldDefinition } from './api'
import { computed, onUnmounted, ref, watch } from 'vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import api from './api'
import { validationValue } from './model'
import { fieldValidationMessage } from './validationMessage'

const props = defineProps<{ field: FieldDefinition, typeLabel: string }>()
const emit = defineEmits<{ close: [] }>()

const type = props.field.config.dataType
const input = ref(type === 'BOOLEAN'
  ? 'true'
  : '')
const useNull = ref(false)
const loading = ref(false)
const controller = new AbortController()
const resultArea = ref<HTMLElement | null>(null)
const outcome = ref<{ type: 'success' | 'error', title: string, message?: string }>()
const placeholder = computed(() => ({
  TEXT: t('请输入测试值'),
  INTEGER: '123',
  DECIMAL: '12.30',
  DATE: '2026-11-09',
  DATETIME: '2026-11-09T09:30:00.123',
  ENUM: t('请输入字典选项编码'),
  ENUM_MULTI: t('请输入 JSON 编码数组，例如 ["A","B"]'),
  ATTACHMENT: '[{"documentId":"42","versionId":"106"}]',
  BOOLEAN: '',
})[type])

watch([input, useNull], () => outcome.value = undefined)
watch(outcome, () => {
  if (resultArea.value)
    resultArea.value.scrollTop = 0
})
onUnmounted(() => controller.abort())

async function submit() {
  if (loading.value)
    return
  let value: unknown
  try {
    value = validationValue(type, useNull.value ? null : input.value)
  }
  catch {
    outcome.value = {
      type: 'error',
      title: t('输入格式错误'),
      message: placeholder.value,
    }
    return
  }

  loading.value = true
  try {
    const { data } = await api.validate(props.field.id, value, controller.signal)
    outcome.value = data.valid
      ? {
          type: 'success',
          title: t('校验通过'),
        }
      : {
          type: 'error',
          title: t('校验失败'),
          message: fieldValidationMessage(props.field.config, value, data),
        }
  }
  catch {
    outcome.value = undefined
    // 请求失败和会话过期由 HTTP 拦截器统一提示。
  }
  finally {
    loading.value = false
  }
}
</script>

<style scoped>
.validation-result {
  block-size: clamp(72px, 16dvh, 112px);
  overflow-y: auto;
}

.validation-message {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
</style>
