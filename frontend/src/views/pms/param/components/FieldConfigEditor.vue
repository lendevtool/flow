<template>
  <div class="field-config">
    <n-alert type="info" :show-icon="false" class="mb-16">
      {{ t('固定字段使用值1～值9存储；扩展字段存入 JSON。已有数据的字段变更会在保存时校验。') }}
    </n-alert>
    <div class="mb-12 font-medium">
      {{ t('固定字段') }}
    </div>
    <n-collapse>
      <n-collapse-item v-for="field in fixed" :key="field.key" :name="field.key">
        <template #header>
          <n-checkbox v-model:checked="field.active" @click.stop>
            {{ field.key }} · {{ field.label }}
          </n-checkbox>
        </template>
        <div class="field-grid">
          <n-form-item :label="t('显示名称')">
            <n-input v-model:value="field.label" :disabled="!field.active" :maxlength="100" />
          </n-form-item>
          <n-form-item :label="t('字段类型')">
            <n-select v-model:value="field.type" :options="typeOptions" :disabled="!field.active" @update:value="resetType(field)" />
          </n-form-item>
          <div class="mb-16 flex flex-wrap gap-16">
            <n-checkbox v-model:checked="field.required" :disabled="!field.active">
              {{ t('必填') }}
            </n-checkbox>
            <n-checkbox v-model:checked="field.showInTable" :disabled="!field.active">
              {{ t('列表显示') }}
            </n-checkbox>
            <n-checkbox v-model:checked="field.hasDefault" :disabled="!field.active">
              {{ t('设置默认值') }}
            </n-checkbox>
          </div>
          <n-form-item v-if="field.hasDefault" :label="t('默认值')">
            <FieldInput v-model:value="field.defaultValue" :field="field" :disabled="!field.active" />
          </n-form-item>
        </div>
        <OptionEditor v-if="field.type === 'select' && field.active" v-model:value="field.options" />
      </n-collapse-item>
    </n-collapse>
    <n-divider />
    <div class="mb-12 flex items-center justify-between">
      <span class="font-medium">{{ t('JSON 扩展字段') }}</span>
      <n-button size="small" secondary :disabled="extra.length >= 50" @click="addExtra">
        <i class="i-fe:plus mr-4" />{{ t('添加字段') }}
      </n-button>
    </div>
    <n-empty v-if="!extra.length" size="small" :description="t('普通字典无需扩展字段')" />
    <div v-for="(field, index) in extra" :key="field._uid" class="extra-field">
      <div class="mb-12 flex items-center justify-between">
        <span>{{ t('扩展字段') }} {{ index + 1 }}</span>
        <n-button size="tiny" quaternary type="error" @click="extra.splice(index, 1)">
          {{ t('移除') }}
        </n-button>
      </div>
      <div class="field-grid">
        <n-form-item :label="t('字段标识')">
          <n-input v-model:value="field.key" :disabled="field.keyLocked" :maxlength="64" :placeholder="t('如 loginRequired')" />
        </n-form-item>
        <n-form-item :label="t('显示名称')">
          <n-input v-model:value="field.label" :maxlength="100" :placeholder="t('如 需要登录')" />
        </n-form-item>
        <n-form-item :label="t('字段类型')">
          <n-select v-model:value="field.type" :options="typeOptions" @update:value="resetType(field)" />
        </n-form-item>
        <n-form-item v-if="field.hasDefault" :label="t('默认值')">
          <FieldInput v-model:value="field.defaultValue" :field="field" />
        </n-form-item>
      </div>
      <div class="mb-12 flex flex-wrap gap-16">
        <n-checkbox v-model:checked="field.required">
          {{ t('必填') }}
        </n-checkbox>
        <n-checkbox v-model:checked="field.showInTable">
          {{ t('列表显示') }}
        </n-checkbox>
        <n-checkbox v-model:checked="field.hasDefault">
          {{ t('设置默认值') }}
        </n-checkbox>
      </div>
      <OptionEditor v-if="field.type === 'select'" v-model:value="field.options" />
    </div>
  </div>
</template>

<script setup>
import { t } from '@/i18n'
import { fieldDefinitions, fieldDraft, getTypeOptions, toPlain, validateConfig } from '../fields'
import FieldInput from './FieldInput.vue'
import OptionEditor from './OptionEditor.vue'

const props = defineProps({
  value: {
    type: Object,
    default: () => ({}),
  },
})
const typeOptions = computed(getTypeOptions)
let uid = 0
const definitions = fieldDefinitions(toPlain(props.value || {}))
function decorate(field, existing = false) {
  return {
    ...fieldDraft(field, existing),
    _uid: ++uid,
  }
}
const fixed = ref(definitions.filter(f => f.storage === 'fixed').map(field => ({
  ...decorate(field),
  active: !!props.value?.fixedFields?.[field.key],
})))
const extra = ref(definitions.filter(f => f.storage === 'extra').map(field => decorate(field, true)))

function addExtra() {
  extra.value.push(decorate({
    key: '',
    label: '',
    type: 'text',
    storage: 'extra',
  }))
}
function resetType(field) {
  field.defaultValue = null
  field.hasDefault = false
  field.options = []
}
function definition(field) {
  const result = {
    label: field.label.trim(),
    type: field.type,
    required: field.required,
    showInTable: field.showInTable,
  }
  if (field.type === 'select')
    result.options = toPlain(field.options)
  if (field.hasDefault)
    result.defaultValue = field.defaultValue
  return result
}
function getConfig() {
  return validateConfig({
    fixedFields: Object.fromEntries(fixed.value.filter(field => field.active).map(field => [
      field.key,
      definition(field),
    ])),
    extraFields: extra.value.map(field => ({
      key: field.key,
      ...definition(field),
    })),
  })
}
defineExpose({ getConfig })
</script>

<style scoped>
.field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.extra-field {
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
  padding: 16px;
  margin-top: 12px;
}
@media (max-width: 600px) {
  .field-grid {
    grid-template-columns: 1fr;
  }
}
</style>
