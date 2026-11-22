<template>
  <n-modal :show="true" preset="card" :title="t('公共维度配置')" class="max-w-95vw w-680" :mask-closable="false" :close-on-esc="!saving" :closable="!saving" @close="emit('close')" @esc="!saving && emit('close')">
    <n-spin v-if="loading" class="min-h-100" />
    <n-alert v-else-if="loadError" type="error">
      {{ loadError }}
      <n-button text @click="load">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <template v-else-if="active">
      <n-tabs v-model:value="selected" type="line">
        <n-tab v-for="field in drafts" :key="field.slot" :name="field.slot" :disabled="saving">
          {{ t('维度 {0}', field.slot) }}
        </n-tab>
      </n-tabs>
      <n-form :disabled="saving" class="mt-16" @submit.prevent="save">
        <div class="max-h-55vh overflow-y-auto">
          <n-form-item :label="t('维度名称')">
            <n-input v-model:value="active.name" :maxlength="100" :input-props="{ 'aria-label': t('维度名称') }" />
          </n-form-item>
          <n-form-item :label="t('英文名称')">
            <n-input v-model:value="active.nameEn" :maxlength="100" :input-props="{ 'aria-label': t('英文名称') }" />
          </n-form-item>
          <n-form-item :label="t('启用')">
            <n-switch v-model:value="active.enable" :aria-label="t('启用')" />
          </n-form-item>
          <n-text depth="3">
            {{ t('停用选项后，历史标记仍可查询。') }}
          </n-text>
          <n-form-item v-for="(option, index) in active.options" :key="option.code" :label="t('选项 {0}', index + 1)">
            <div class="grid w-full gap-8 sm:grid-cols-2">
              <n-input v-model:value="option.name" :maxlength="100" :placeholder="t('名称')" :input-props="{ 'aria-label': t('选项 {0}', index + 1) }" />
              <n-input v-model:value="option.nameEn" :maxlength="100" :placeholder="t('英文名称')" :input-props="{ 'aria-label': t('英文名称') }" />
            </div>
            <n-switch v-model:value="option.enable" class="ml-12" :aria-label="t('启用')" />
            <n-button v-if="!option.parameter" text class="ml-12" @click="active.options.splice(index, 1)">
              {{ t('删除') }}
            </n-button>
          </n-form-item>
        </div>
        <n-button v-if="canCreate" class="my-12" @click="addOption">
          {{ t('添加选项') }}
        </n-button>
        <n-alert v-if="error" type="error" class="mb-12">
          {{ error }}
        </n-alert>
        <n-alert v-else-if="saved" type="success" class="mb-12">
          {{ t('保存成功') }}
        </n-alert>
        <n-space justify="end" class="mt-12">
          <n-button :disabled="saving" @click="emit('close')">
            {{ t('关闭') }}
          </n-button>
          <n-button type="primary" attr-type="submit" :loading="saving">
            {{ t('保存当前维度') }}
          </n-button>
        </n-space>
      </n-form>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import type { PublicDimensionDraft } from './publicDimensionSettings'
import { computed, onMounted, ref, watch } from 'vue'
import { t } from '@/i18n'
import { newWorkbenchKey, workbenchError } from './model'
import { loadPublicDimensionSettings, savePublicDimension } from './publicDimensionSettings'

defineProps<{ canCreate: boolean }>()
const emit = defineEmits<{ close: [], changed: [] }>()
const drafts = ref<PublicDimensionDraft[]>([])
const selected = ref(1)
const active = computed(() => drafts.value.find(draft => draft.slot === selected.value))
const loading = ref(true)
const saving = ref(false)
const saved = ref(false)
const loadError = ref('')
const error = ref('')

watch(selected, () => {
  error.value = ''
  saved.value = false
})
onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    drafts.value = await loadPublicDimensionSettings()
    if (!drafts.value.length)
      throw new Error(t('待办公共维度参数尚未初始化。'))
    selected.value = drafts.value[0].slot
  }
  catch (cause) {
    loadError.value = workbenchError(cause, t('加载失败，请重试'))
  }
  finally {
    loading.value = false
  }
}

function addOption() {
  active.value?.options.push({
    code: `OPTION_${newWorkbenchKey()}`,
    name: '',
    nameEn: '',
    enable: true,
  })
}

async function save() {
  const draft = active.value
  if (!draft || saving.value)
    return
  saved.value = false
  error.value = ''
  if (!draft.name.trim() || draft.options.some(option => !option.name.trim()) || (draft.enable && !draft.options.length)) {
    error.value = t('请填写维度和选项的名称。启用维度时，至少需要一个选项。')
    return
  }
  saving.value = true
  try {
    await savePublicDimension(draft)
    saved.value = true
  }
  catch (cause) {
    error.value = workbenchError(cause, t('保存失败，请重试'))
  }
  finally {
    saving.value = false
    emit('changed')
  }
}
</script>
