<template>
  <template v-if="!enumValues && kind === 'object' && isJsonObject(value)">
    <fieldset v-if="path.length" class="docgen-field-group">
      <legend>{{ title }}<span v-if="required" aria-hidden="true"> *</span></legend>
      <p v-if="description" class="docgen-field-description">
        {{ description }}
      </p>
      <SchemaField v-for="key in keys" :key="key" :schema="properties[key]" :root="root" :path="[...path, key]" :value="schemaProperty(value, key)" :required="requiredKeys.includes(key)" :label="key" @change="forward" />
      <n-button v-if="!required" text size="small" :aria-label="t('移除 {0}', title)" @click="change(undefined)">
        {{ t('移除字段') }}
      </n-button>
    </fieldset>
    <template v-else>
      <SchemaField v-for="key in keys" :key="key" :schema="properties[key]" :root="root" :path="[key]" :value="schemaProperty(value, key)" :required="requiredKeys.includes(key)" :label="key" @change="forward" />
      <n-empty v-if="!keys.length" :description="t('此模板没有可用的表单字段，请使用 JSON 编辑')" />
    </template>
  </template>
  <fieldset v-else-if="!enumValues && kind === 'array' && Array.isArray(value)" class="docgen-field-group">
    <legend>{{ title }}<span v-if="required" aria-hidden="true"> *</span></legend>
    <p v-if="description" class="docgen-field-description">
      {{ description }}
    </p>
    <div v-for="(entry, index) in value" :key="index" class="docgen-array-entry">
      <SchemaField :schema="resolved.items" :root="root" :path="[...path, index]" :value="entry" :label="t('第 {0} 项', index + 1)" required @change="forward" />
      <n-button size="small" text :aria-label="t('删除第 {0} 项', index + 1)" @click="emit('change', [...path, index], undefined)">
        {{ t('删除') }}
      </n-button>
    </div>
    <n-space>
      <n-button :aria-label="t('添加 {0}', title)" size="small" @click="emit('change', [...path, value.length], defaultFieldValue(itemSchema))">
        {{ t('添加一项') }}
      </n-button>
      <n-button v-if="!required" size="small" text :aria-label="t('移除 {0}', title)" @click="change(undefined)">
        {{ t('移除字段') }}
      </n-button>
    </n-space>
  </fieldset>
  <n-form-item v-else :label="title" :required="required" :show-feedback="false" class="docgen-field">
    <div class="min-w-0 w-full">
      <div v-if="description" class="docgen-field-description">
        {{ description }}
      </div>
      <div class="docgen-field-controls">
        <n-select
          v-if="enumValues"
          :value="enumIndex"
          :options="enumOptions"
          :input-props="{ 'aria-label': title }"
          :placeholder="t('请选择')"
          :clearable="!isArrayItem"
          @update:value="index => change(index == null ? undefined : enumValues?.[index])"
        />
        <template v-else-if="(kind === 'object' || kind === 'array') && value === undefined">
          <n-button size="small" :aria-label="t('添加 {0}', title)" @click="change(defaultFieldValue(resolved))">
            {{ t(kind === 'object' ? '添加对象' : '添加数组') }}
          </n-button>
        </template>
        <template v-else-if="value === null || kind === 'null'">
          <code>null</code>
          <n-button v-if="kind !== 'null'" text size="small" :aria-label="t('填写 {0}', title)" @click="change(defaultFieldValue(resolved))">
            {{ t('填写值') }}
          </n-button>
        </template>
        <n-input
          v-else-if="kind === 'string'"
          :value="typeof value === 'string' ? value : ''"
          :type="resolved.format === 'date' ? 'text' : 'textarea'"
          :autosize="{ minRows: 1, maxRows: 6 }"
          :input-props="{ 'aria-label': title }"
          :placeholder="resolved.format === 'date' ? 'YYYY-MM-DD' : undefined"
          @update:value="change"
        />
        <n-input-number
          v-else-if="kind === 'integer' || kind === 'number'"
          :value="typeof value === 'number' ? value : null"
          :input-props="{ 'aria-label': title }"
          :show-button="false"
          class="w-full"
          @update:value="number => (number !== null || !isArrayItem) && change(number ?? undefined)"
        />
        <n-select
          v-else-if="kind === 'boolean'"
          :value="typeof value === 'boolean' ? String(value) : null"
          :options="booleanOptions"
          :input-props="{ 'aria-label': title }"
          :placeholder="t('请选择')"
          :clearable="!isArrayItem"
          @update:value="selection => change(selection === null ? undefined : selection === 'true')"
        />
        <span v-else>{{ t('请使用 JSON 编辑此字段') }}</span>
        <n-button v-if="!enumValues && kind === 'string' && value === undefined" text size="small" :aria-label="t('将 {0} 留空', title)" @click="change('')">
          {{ t('设为空字符串') }}
        </n-button>
        <n-button v-if="!enumValues && nullable && value !== null" text size="small" :aria-label="t('将 {0} 设为 null', title)" @click="change(null)">
          {{ t('设为 null') }}
        </n-button>
        <n-button v-if="!enumValues && !required && value !== undefined" text size="small" :aria-label="t('移除 {0}', title)" @click="change(undefined)">
          {{ t('移除字段') }}
        </n-button>
      </div>
    </div>
  </n-form-item>
</template>

<script setup lang="ts">
import type { FieldPath, JsonObject, JsonValue } from './input'
import { computed } from 'vue'
import { t } from '@/i18n'
import { isJsonObject } from './input'
import { defaultFieldValue, fieldKind, resolveFieldSchema, schemaProperty } from './schema'

const props = defineProps<{
  schema: JsonValue | undefined
  root: JsonObject
  value: JsonValue | undefined
  path: FieldPath
  label?: string
  required?: boolean
}>()
const emit = defineEmits<{ change: [path: FieldPath, value: JsonValue | undefined] }>()
const isArrayItem = computed(() => typeof props.path.at(-1) === 'number')
const resolved = computed(() => resolveFieldSchema(props.schema, props.root))
const kind = computed(() => fieldKind(resolved.value))
const title = computed(() => typeof resolved.value.title === 'string'
  ? resolved.value.title
  : props.label ?? '')
const description = computed(() => typeof resolved.value.description === 'string'
  ? resolved.value.description
  : '')
const properties = computed(() => isJsonObject(resolved.value.properties)
  ? resolved.value.properties
  : {})
const keys = computed(() => Object.keys(properties.value))
const requiredKeys = computed(() => Array.isArray(resolved.value.required)
  ? resolved.value.required
  : [])
const itemSchema = computed(() => resolveFieldSchema(resolved.value.items, props.root))
const nullable = computed(() => resolved.value.type === 'null' || (Array.isArray(resolved.value.type) && resolved.value.type.includes('null')))
const enumValues = computed(() => Array.isArray(resolved.value.enum)
  ? resolved.value.enum
  : undefined)
const enumIndex = computed(() => {
  const index = enumValues.value?.findIndex(item => JSON.stringify(item) === JSON.stringify(props.value))
  return index === undefined || index < 0
    ? null
    : index
})
const enumOptions = computed(() => enumValues.value?.map((item, index) => ({
  value: index,
  label: typeof item === 'string' && item
    ? item
    : JSON.stringify(item),
})))
const booleanOptions = computed(() => [
  {
    value: 'true',
    label: t('是（true）'),
  },
  {
    value: 'false',
    label: t('否（false）'),
  },
])

function change(value: JsonValue | undefined) {
  emit('change', props.path, value)
}
function forward(path: FieldPath, value: JsonValue | undefined) {
  emit('change', path, value)
}
</script>

<style scoped>
.docgen-field-group {
  min-width: 0;
  margin: 0 0 12px;
  padding: 12px;
  border: 1px solid var(--docgen-border, #87878755);
  border-radius: 6px;
}

.docgen-field-group legend {
  padding-inline: 6px;
  white-space: normal;
  overflow-wrap: anywhere;
}

.docgen-field-controls,
.docgen-array-entry {
  display: flex;
  align-items: center;
  gap: 12px;
}

.docgen-array-entry {
  align-items: flex-start;
  margin-bottom: 8px;
}

.docgen-array-entry > :first-child {
  flex: 1;
  min-width: 0;
}

.docgen-field-controls > * {
  min-width: 0;
}

.docgen-field-controls > button {
  flex-shrink: 0;
}

.docgen-field {
  margin-bottom: 12px;
}

.docgen-field-description {
  margin-bottom: 6px;
  opacity: 0.75;
  overflow-wrap: anywhere;
}

@media (max-width: 600px) {
  .docgen-field-controls {
    flex-wrap: wrap;
  }
}
</style>
