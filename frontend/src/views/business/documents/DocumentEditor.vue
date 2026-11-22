<template>
  <n-modal
    :show="true"
    preset="card"
    :title="title"
    style="width: min(600px, 94vw)"
    :closable="!saving"
    :mask-closable="false"
    :close-on-esc="!saving"
    @update:show="show => !show && close()"
  >
    <n-form id="document-editor" :disabled="saving" @submit.prevent="save">
      <template v-if="mode !== 'version'">
        <n-form-item :label="t('文档名称')" required>
          <n-input v-model:value="form.name" :input-props="{ 'aria-label': t('文档名称') }" :maxlength="255" />
        </n-form-item>
        <div class="document-editor-grid">
          <n-form-item :label="t('部门')">
            <n-select
              v-model:value="form.departmentId"
              :input-props="{ 'aria-label': t('部门') }"
              :options="departmentOptions"
              filterable
              clearable
            />
          </n-form-item>
          <n-form-item :label="t('产品')">
            <n-select
              v-model:value="form.productId"
              :input-props="{ 'aria-label': t('产品') }"
              :options="productOptions"
              filterable
              clearable
            />
          </n-form-item>
          <n-form-item v-for="dimension in dimensions" :key="dimension.slot" :label="dimensionLabel(dimension)">
            <n-select
              v-if="dimension.inputType === 'SELECT'"
              v-model:value="form.dimensions[dimension.slot]"
              :input-props="{ 'aria-label': dimensionLabel(dimension) }"
              :options="dimensionSelectOptions(dimension, initial?.dimensions[dimension.slot] ?? null)"
              :disabled="!dimension.enabled"
              filterable
              clearable
            />
            <n-input
              v-else
              :value="form.dimensions[dimension.slot] ?? ''"
              :input-props="{ 'aria-label': dimensionLabel(dimension) }"
              :disabled="!dimension.enabled"
              :maxlength="255"
              clearable
              @update:value="form.dimensions[dimension.slot] = $event"
            />
          </n-form-item>
        </div>
      </template>
      <p v-else class="mb-16 break-words">
        {{ initial?.name }}
      </p>
      <n-form-item v-if="mode !== 'edit'" :label="t('文件')" required>
        <n-upload v-model:file-list="files" :default-upload="false" :max="1" :disabled="saving">
          <n-button :disabled="saving">
            {{ t('请选择文件') }}
          </n-button>
        </n-upload>
      </n-form-item>
    </n-form>

    <template #footer>
      <n-space justify="end">
        <n-button :disabled="saving" @click="close">
          {{ t('取消') }}
        </n-button>
        <n-button
          type="primary"
          attr-type="submit"
          form="document-editor"
          :loading="saving"
          :disabled="!canSave"
        >
          {{ t(mode === 'edit' ? '保存' : '上传') }}
        </n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import type { UploadFileInfo } from 'naive-ui'
import type { DimensionValues, DocumentMetadata, DocumentOptions, DocumentRecord } from './api'
import type { DocumentFilters } from './model'
import { computed, reactive, ref } from 'vue'
import { t } from '@/i18n'
import api from './api'
import { dimensionLabel, dimensionSelectOptions, dimensionSlots, documentSelectOptions } from './model'

const props = defineProps<{
  mode: 'create' | 'edit' | 'version'
  initial: DocumentRecord | null
  options: DocumentOptions
}>()

const emit = defineEmits<{
  close: []
  saved: [mode: 'create' | 'edit' | 'version']
}>()

const form = reactive<Omit<DocumentMetadata, 'dimensions'> & Pick<DocumentFilters, 'dimensions'>>({
  name: props.initial?.name ?? '',
  departmentId: props.initial?.departmentId ?? null,
  productId: props.initial?.productId ?? null,
  dimensions: { ...props.initial?.dimensions },
})
const files = ref<UploadFileInfo[]>([])
const saving = ref(false)
const file = computed(() => files.value[0]?.file)
const canSave = computed(() => {
  if (props.mode === 'version')
    return Boolean(file.value)
  return Boolean(form.name.trim()) && (props.mode === 'edit' || Boolean(file.value))
})

const title = computed(() => ({
  create: t('上传文档'),
  edit: t('编辑文档'),
  version: t('上传新版本'),
})[props.mode])
const departmentOptions = computed(() => documentSelectOptions(props.options.departments, props.initial?.departmentId ?? null))
const productOptions = computed(() => documentSelectOptions(props.options.products, props.initial?.productId ?? null))
const dimensions = computed(() => props.options.dimensions.filter(dimension => dimension.enabled || form.dimensions[dimension.slot]))

function close() {
  if (!saving.value)
    emit('close')
}

async function save() {
  if (saving.value || !canSave.value)
    return
  const { initial, mode } = props
  const selectedFile = file.value
  const dimensions: DimensionValues = {}
  for (const slot of dimensionSlots) {
    const value = form.dimensions[slot]?.trim()
    if (value)
      dimensions[slot] = value
  }
  const metadata: DocumentMetadata = {
    name: form.name.trim(),
    departmentId: form.departmentId,
    productId: form.productId,
    dimensions,
  }
  saving.value = true
  try {
    if (mode === 'version' && initial && selectedFile)
      await api.addVersion(initial.id, selectedFile)
    else if (mode === 'edit' && initial)
      await api.update(initial.id, metadata)
    else if (mode === 'create' && selectedFile)
      await api.create(metadata, selectedFile)
    else
      return
    emit('saved', mode)
  }
  catch {
    // 统一错误提示保留表单，便于修正后重试。
  }
  finally {
    saving.value = false
  }
}
</script>

<style scoped>
.document-editor-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr));
  column-gap: 16px;
}
</style>
