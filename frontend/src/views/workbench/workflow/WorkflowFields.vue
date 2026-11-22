<template>
  <n-card v-if="groups.length" size="small" class="workflow-fields" :class="{ readonly: !editable.length }">
    <div v-for="group in groups" :key="String(group.editable)" class="fields-grid" :class="{ 'fields-grid-readonly': !group.editable }">
      <div v-for="field in group.fields" :key="field.code" class="form-field">
        <label :for="`workflow-field-${field.code}`">
          {{ field.config ? displayName(field.config.name, field.config.nameEn) : field.code }}
          <span v-if="required.includes(field.code)" class="required">*</span>
        </label>
        <div v-if="field.config?.dataType === 'ATTACHMENT'" class="file-list">
          <div v-for="file in files(field.code)" :key="file.versionId" class="file-row">
            <span>{{ attachmentNames[file.versionId] || file.versionId }}</span>
            <n-button v-if="instanceId && savedFile(field.code, file)" text type="primary" @click="$emit('download', file)">
              {{ t('下载') }}
            </n-button>
            <n-button v-if="group.editable" text type="error" :disabled="disabled" @click="removeFile(field.code, file.versionId)">
              {{ t('移除') }}
            </n-button>
          </div>
          <label v-if="group.editable" class="upload-control">
            <span>{{ t(uploading === field.code ? '正在上传' : '上传附件') }}</span>
            <input :id="`workflow-field-${field.code}`" type="file" multiple :disabled="disabled || !!uploading" @change="chooseFiles(field.code, $event)">
          </label>
          <span v-else-if="!files(field.code).length">—</span>
        </div>
        <span v-else-if="!group.editable" class="read-value">{{ asLabel(field) }}</span>
        <n-select
          v-else-if="field.config?.dataType === 'BOOLEAN'"
          :value="booleanText(field.code)"
          :options="booleanOptions"
          :disabled="disabled"
          clearable
          @update:value="set(field.code, $event == null ? null : $event === 'true')"
        />
        <n-select v-else-if="field.config?.dataType === 'ENUM_MULTI'" :value="asList(field.code)" multiple clearable :disabled="disabled" :options="choices(field)" @update:value="set(field.code, $event)" />
        <n-select v-else-if="field.config?.dataType === 'ENUM'" :value="asText(field.code)" clearable :disabled="disabled" :options="choices(field)" @update:value="set(field.code, $event)" />
        <input
          v-else-if="field.config?.dataType === 'DATE' || field.config?.dataType === 'DATETIME'"
          :id="`workflow-field-${field.code}`"
          class="date-input"
          :type="field.config.dataType === 'DATE' ? 'date' : 'datetime-local'"
          :value="asText(field.code) ?? ''"
          :disabled="disabled"
          @change="set(field.code, ($event.target as HTMLInputElement).value || null)"
        >
        <n-input v-else :input-props="{ id: `workflow-field-${field.code}` }" :value="asText(field.code)" clearable :disabled="disabled" @update:value="set(field.code, $event)" />
      </div>
    </div>
  </n-card>
</template>

<script setup lang="ts">
import type { AttachmentRef, WorkflowValue, WorkflowValues } from './api'
import type { WorkflowDefinition } from '@/views/pms/workflow/api'
import { computed } from 'vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'

type Field = WorkflowDefinition['fields'][number]
const props = defineProps<{
  fields: Field[]
  values: WorkflowValues
  saved: WorkflowValues
  editable: string[]
  required: string[]
  instanceId?: number
  uploading: string
  attachmentNames: Record<string, string>
  disabled: boolean
}>()
const emit = defineEmits<{ change: [code: string, value: WorkflowValue], upload: [code: string, files: File[]], download: [AttachmentRef] }>()
const groups = computed(() => [false, true].map(editable => ({
  editable,
  fields: props.fields.filter(field => props.editable.includes(field.code) === editable),
})).filter(group => group.fields.length))

const booleanOptions = [
  {
    value: 'true',
    label: t('是'),
  },
  {
    value: 'false',
    label: t('否'),
  },
]
function booleanText(code: string) {
  const value = props.values[code]
  return typeof value === 'boolean'
    ? String(value)
    : null
}
function choices(field: Field) {
  return (field.options ?? []).map(option => ({
    label: displayName(option.name, option.nameEn),
    value: option.code,
  }))
}
function asText(code: string) {
  const value = props.values[code]
  return typeof value === 'string'
    ? value
    : null
}
function asList(code: string) {
  const value = props.values[code]
  return Array.isArray(value)
    ? value.filter(item => typeof item === 'string')
    : []
}
function files(code: string, values: WorkflowValues = props.values) {
  const value = values[code]
  return Array.isArray(value)
    ? value.filter(item => typeof item === 'object')
    : []
}
function savedFile(code: string, file: AttachmentRef) {
  return files(code, props.saved).some(item => item.versionId === file.versionId)
}
function asLabel(field: Field) {
  const value = Object.hasOwn(props.values, field.code)
    ? props.values[field.code]
    : field.config?.defaultValue
  if (value == null)
    return '—'
  if (typeof value === 'boolean')
    return t(value ? '是' : '否')
  const options = new Map(choices(field).map(option => [option.value, option.label]))
  return Array.isArray(value)
    ? value.map(item => typeof item === 'string'
        ? options.get(item) ?? item
        : '').join('、')
    : options.get(value) ?? value
}
function set(code: string, value: WorkflowValue) {
  emit('change', code, value)
}
function removeFile(code: string, version: string) {
  set(code, files(code).filter(item => item.versionId !== version))
}
function chooseFiles(code: string, event: Event) {
  const input = event.target as HTMLInputElement
  const selected = [
    ...(input.files ?? []),
  ]
  if (selected.length)
    emit('upload', code, selected)
  input.value = ''
}
</script>

<style scoped>
.fields-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 18px;
}
.fields-grid-readonly + .fields-grid {
  margin-top: 12px;
  border-top: 1px solid var(--n-border-color);
  padding-top: 12px;
}
.form-field {
  display: grid;
  grid-template-columns: minmax(70px, 112px) minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.form-field > label {
  text-align: right;
}
.readonly {
  background: #8881;
}
.read-value {
  overflow-wrap: anywhere;
  padding: 4px 8px;
}
.required {
  color: #bc3a3a;
}
.file-list {
  display: grid;
  gap: 5px;
  min-width: 0;
}
.file-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
  overflow-wrap: anywhere;
}
.upload-control {
  justify-self: start;
  cursor: pointer;
  position: relative;
  border: 1px solid #8886;
  border-radius: 5px;
  padding: 3px 8px;
}
.upload-control input {
  position: absolute;
  inset: 0;
  opacity: 0;
  width: 100%;
  cursor: pointer;
}
.upload-control:focus-within {
  outline: 2px solid #18805b;
}
.date-input {
  border: 1px solid #8886;
  border-radius: 4px;
  background: transparent;
  padding: 5px 8px;
  color: inherit;
}
@media (max-width: 760px) {
  .fields-grid {
    grid-template-columns: 1fr;
  }
}
</style>
