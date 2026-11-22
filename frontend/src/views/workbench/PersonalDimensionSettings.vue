<template>
  <n-modal :show="true" preset="card" :title="t('个人维度配置')" class="max-w-95vw w-680" :mask-closable="false" :closable="!saving" @close="emit('close')" @esc="!saving && emit('close')">
    <n-tabs v-model:value="selected" type="line">
      <n-tab v-for="slot in 5" :key="slot" :name="slot" :disabled="saving">
        {{ t('维度 {0}', slot) }}
      </n-tab>
    </n-tabs>
    <n-form v-if="active" :disabled="saving" class="mt-16" @submit.prevent="save">
      <n-form-item :label="t('维度名称')">
        <n-input v-model:value="active.name" :maxlength="100" />
      </n-form-item>
      <n-form-item :label="t('启用')">
        <n-switch v-model:value="active.enabled" />
      </n-form-item>
      <n-text depth="3">
        {{ t('不添加选项时，填写自由文本。停用选项后，历史标记仍可查询。') }}
      </n-text>
      <n-form-item v-for="(option, index) in active.options" :key="option.key" :label="t('选项 {0}', index + 1)">
        <n-input v-model:value="option.name" :maxlength="100" />
        <n-switch v-model:value="option.enabled" class="ml-12" :aria-label="t('启用')" />
        <n-button text class="ml-12" @click="active.options.splice(index, 1)">
          {{ t('删除') }}
        </n-button>
      </n-form-item>
      <n-button class="mb-12" @click="addOption">
        {{ t('添加选项') }}
      </n-button>
      <n-alert v-if="error" type="error" class="mb-12">
        {{ error }}
      </n-alert>
      <n-space justify="end">
        <n-button :disabled="saving" @click="emit('close')">
          {{ t('关闭') }}
        </n-button>
        <n-button type="primary" attr-type="submit" :loading="saving">
          {{ t('保存当前维度') }}
        </n-button>
      </n-space>
    </n-form>
  </n-modal>
</template>

<script setup lang="ts">
import type { PersonalDimension } from './api'
import { computed, ref, watch } from 'vue'
import { t } from '@/i18n'
import api from './api'
import { newWorkbenchKey, workbenchError } from './model'

const props = defineProps<{ dimensions: PersonalDimension[] }>()
const emit = defineEmits<{ close: [], saved: [] }>()
const slots = ref(Array.from({ length: 5 }, (_, index): PersonalDimension => {
  const slot = index + 1
  const initial = props.dimensions.find(item => item.slot === slot)
  return initial
    ? {
        ...initial,
        options: initial.options.map(option => ({ ...option })),
      }
    : {
        slot,
        name: '',
        enabled: false,
        options: [],
      }
}))
const selected = ref(1)
const active = computed(() => slots.value[selected.value - 1])
const saving = ref(false)
const error = ref('')
watch(selected, () => {
  error.value = ''
})

function addOption() {
  active.value.options.push({
    key: newWorkbenchKey(),
    name: '',
    enabled: true,
  })
}

async function save() {
  if (saving.value)
    return
  const field = active.value
  const name = field.name.trim()
  const options = field.options.map(option => ({
    ...option,
    name: option.name.trim(),
  }))
  error.value = !name || options.some(option => !option.name)
    ? t('请填写维度和选项的名称。')
    : ''
  if (error.value)
    return
  saving.value = true
  try {
    const result = await api.savePersonalDimension({ ...field, name, options })
    slots.value[field.slot - 1] = result.data
    emit('saved')
  }
  catch (cause) {
    error.value = workbenchError(cause, t('保存失败，请重试'))
  }
  finally {
    saving.value = false
  }
}
</script>
