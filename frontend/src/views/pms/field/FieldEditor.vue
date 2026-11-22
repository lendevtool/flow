<template>
  <component
    :is="readonly ? NModal : NDrawer"
    v-bind="readonly ? modalProps : drawerProps"
    :show="true"
    :mask-closable="!saving"
    :close-on-esc="!saving"
    :aria-label="title"
    @update:show="close"
  >
    <component
      :is="readonly ? NCard : NDrawerContent"
      class="field-editor"
      :class="{ 'field-editor-modal': readonly }"
      :title="title"
      :closable="!saving"
      @close="close"
    >
      <n-form
        label-placement="left"
        label-align="left"
        label-width="clamp(88px, 28vw, 152px)"
        :disabled="readonly || saving"
      >
        <div class="field-grid">
          <n-form-item :label="t('字段编码')" required>
            <n-input v-model:value="form.code" :maxlength="64" :disabled="!!initial" placeholder="contract_amount" />
          </n-form-item>
          <n-form-item :label="t('字段类型')" required>
            <n-select :value="form.dataType" :options="typeOptions" @update:value="selectType" />
          </n-form-item>
          <n-form-item :label="t('中文名称')" required>
            <n-input v-model:value="form.name" :maxlength="100" />
          </n-form-item>
          <n-form-item :label="t('英文名称')" required>
            <n-input v-model:value="form.nameEn" :maxlength="100" />
          </n-form-item>
        </div>
        <n-space class="mb-16">
          <n-checkbox v-model:checked="form.required">
            {{ t('必填') }}
          </n-checkbox>
          <n-checkbox v-model:checked="form.enable">
            {{ t('启用') }}
          </n-checkbox>
        </n-space>

        <n-divider title-placement="left">
          {{ t('字段校验') }}
        </n-divider>

        <template v-if="form.dataType === 'TEXT'">
          <div class="field-grid">
            <n-form-item :label="t('最少字符数')">
              <n-input-number v-model:value="form.textMinLength" :min="0" :max="2147483647" :precision="0" />
            </n-form-item>
            <n-form-item :label="t('最多字符数')">
              <n-input-number v-model:value="form.textMaxLength" :min="0" :max="2147483647" :precision="0" />
            </n-form-item>
            <n-form-item :label="t('文本格式')">
              <n-select v-model:value="form.textFormat" :options="formatOptions" clearable />
            </n-form-item>
            <n-form-item :label="t('正则表达式')">
              <n-input :value="form.textPattern ?? ''" :maxlength="512" clearable @update:value="form.textPattern = $event || null" />
            </n-form-item>
          </div>
          <n-form-item :label="t('限制字符类别')">
            <n-switch :value="form.textCharLimitEnabled" @update:value="toggleCharacterLimit" />
          </n-form-item>
          <n-form-item v-if="form.textCharLimitEnabled" :label="t('允许字符')" required>
            <n-checkbox-group :value="characters" @update:value="setCharacters(form, $event)">
              <n-space>
                <n-checkbox
                  v-for="option in characterOptions"
                  :key="option.value"
                  :value="option.value"
                  :label="option.label"
                  :disabled="option.disabled"
                />
              </n-space>
            </n-checkbox-group>
          </n-form-item>
          <n-text v-if="form.textCharLimitEnabled" depth="3">
            {{ t('可多选。每个字符须属于任意一个已选类别。连接符为 - 和 _。') }}
          </n-text>
        </template>

        <template v-else-if="form.dataType === 'INTEGER' || form.dataType === 'DECIMAL'">
          <n-form-item :label="t('仅允许正数')">
            <n-switch v-model:value="form.numberPositiveOnly" />
          </n-form-item>
          <div v-if="form.dataType === 'INTEGER'" class="field-grid">
            <n-form-item :label="t('最小值')">
              <n-input :value="form.integerMin ?? ''" :maxlength="21" clearable @update:value="form.integerMin = $event || null" />
            </n-form-item>
            <n-form-item :label="t('最大值')">
              <n-input :value="form.integerMax ?? ''" :maxlength="21" clearable @update:value="form.integerMax = $event || null" />
            </n-form-item>
          </div>
          <div v-else class="field-grid">
            <n-form-item :label="t('最小值')">
              <n-input :value="form.decimalMin ?? ''" :maxlength="40" clearable @update:value="form.decimalMin = $event || null" />
            </n-form-item>
            <n-form-item :label="t('最大值')">
              <n-input :value="form.decimalMax ?? ''" :maxlength="40" clearable @update:value="form.decimalMax = $event || null" />
            </n-form-item>
            <n-form-item :label="t('数字总位数')">
              <n-input-number v-model:value="form.decimalPrecision" :min="1" :max="38" :precision="0" />
            </n-form-item>
            <n-form-item :label="t('小数位数')">
              <n-input-number v-model:value="form.decimalScale" :min="0" :max="18" :precision="0" />
            </n-form-item>
          </div>
        </template>

        <div v-else-if="form.dataType === 'DATE'" class="field-grid">
          <n-form-item :label="t('最早日期')">
            <n-date-picker v-model:formatted-value="form.dateMin" value-format="yyyy-MM-dd" clearable />
          </n-form-item>
          <n-form-item :label="t('最晚日期')">
            <n-date-picker v-model:formatted-value="form.dateMax" value-format="yyyy-MM-dd" clearable />
          </n-form-item>
        </div>

        <div v-else-if="form.dataType === 'DATETIME'" class="field-grid">
          <n-form-item :label="t('最早时间')">
            <n-date-picker v-model:formatted-value="form.dateTimeMin" type="datetime" :value-format="timeFormat" clearable />
          </n-form-item>
          <n-form-item :label="t('最晚时间')">
            <n-date-picker v-model:formatted-value="form.dateTimeMax" type="datetime" :value-format="timeFormat" clearable />
          </n-form-item>
        </div>

        <template v-else-if="isEnum(form.dataType)">
          <n-form-item :label="t('参数分类')" required>
            <n-select
              :value="form.paramGroupId"
              :options="dictionaryOptions"
              :loading="loadingGroups"
              clearable
              filterable
              remote
              @search="searchGroups"
              @update:value="changeGroup"
            />
          </n-form-item>
          <div v-if="form.dataType === 'ENUM_MULTI'" class="field-grid">
            <n-form-item :label="t('最少选项数')">
              <n-input-number v-model:value="form.enumMinItems" :min="0" :max="2147483647" :precision="0" />
            </n-form-item>
            <n-form-item :label="t('最多选项数')">
              <n-input-number v-model:value="form.enumMaxItems" :min="0" :max="2147483647" :precision="0" />
            </n-form-item>
          </div>
        </template>
        <n-text v-else-if="form.dataType === 'BOOLEAN'" depth="3">
          {{ t('布尔字段接受真或假。') }}
        </n-text>

        <n-form-item v-if="form.dataType !== 'ATTACHMENT'" class="mt-12" :label="t('默认值')">
          <n-select
            v-if="form.dataType === 'ENUM_MULTI'"
            :value="multiDefault"
            :options="enumOptions"
            :loading="loadingOptions"
            :disabled="!form.paramGroupId || optionsFailed"
            multiple
            clearable
            @update:value="changeMultiDefault"
          />
          <n-select
            v-else-if="form.dataType === 'ENUM'"
            :value="stringDefault"
            :options="enumOptions"
            :loading="loadingOptions"
            :disabled="!form.paramGroupId || optionsFailed"
            clearable
            @update:value="form.defaultValue = $event"
          />
          <n-select
            v-else-if="form.dataType === 'BOOLEAN'"
            :value="booleanDefault"
            :options="booleanOptions"
            clearable
            @update:value="form.defaultValue = $event == null ? null : $event === 'true'"
          />
          <n-date-picker
            v-else-if="form.dataType === 'DATE' || form.dataType === 'DATETIME'"
            :key="form.dataType"
            :formatted-value="stringDefault"
            :type="form.dataType === 'DATE' ? 'date' : 'datetime'"
            :value-format="form.dataType === 'DATE' ? 'yyyy-MM-dd' : timeFormat"
            clearable
            @update:formatted-value="form.defaultValue = $event"
          />
          <n-input v-else :value="stringDefault ?? ''" clearable @update:value="form.defaultValue = $event || null" />
        </n-form-item>
        <n-alert v-if="isEnum(form.dataType) && (groupsFailed || optionsFailed)" type="error" class="mb-12">
          {{ t('加载字典失败') }}
          <n-button text @click="retryDictionaries">
            {{ t('重试') }}
          </n-button>
        </n-alert>
        <n-form-item :label="t('备注')">
          <n-input v-model:value="form.remark" type="textarea" :maxlength="500" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button :disabled="saving" @click="close">
            {{ t(readonly ? '关闭' : '取消') }}
          </n-button>
          <n-button v-if="!readonly" type="primary" :loading="saving" :disabled="!canSave" @click="save">
            {{ t('保存') }}
          </n-button>
        </n-space>
      </template>
    </component>
  </component>
</template>

<script setup lang="ts">
import type { Dictionary, FieldDefinition, Metadata, Option } from './api'
import { useDebounceFn } from '@vueuse/core'
import { NCard, NDrawer, NDrawerContent, NModal } from 'naive-ui'
import { computed, ref, watch } from 'vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import api from './api'
import { changeFieldType, isDataType, isEnum, newField, pickerDateTime, selectedCharacters, setCharacters } from './model'

const props = defineProps<{ initial: FieldDefinition | null, readonly: boolean, metadata: Metadata }>()
const emit = defineEmits<{ close: [], saved: [] }>()
const title = computed(() => t(props.readonly
  ? '字段详情'
  : props.initial
    ? '编辑字段'
    : '新增字段'))
const modalProps = { 'role': 'dialog', 'aria-modal': true }
const drawerProps = { placement: 'right', width: 'min(880px, 100vw)' } as const
const firstType = props.metadata.types.find(option => option.enable && isDataType(option.code))?.code ?? ''
const form = ref(props.initial
  ? { ...props.initial.config }
  : newField(isDataType(firstType)
      ? firstType
      : 'TEXT'))

const timeFormat = 'yyyy-MM-dd\'T\'HH:mm:ss.SSS'
if (form.value.dataType === 'DATETIME') {
  form.value.dateTimeMin = pickerDateTime(form.value.dateTimeMin)
  form.value.dateTimeMax = pickerDateTime(form.value.dateTimeMax)
  if (typeof form.value.defaultValue === 'string')
    form.value.defaultValue = pickerDateTime(form.value.defaultValue)
}

const saving = ref(false)
const dictionaries = ref<Dictionary[]>([])
const values = ref<Option[]>([])
const loadingGroups = ref(false)
const loadingOptions = ref(false)
const groupsFailed = ref(false)
const optionsFailed = ref(false)
let groupRequest = 0
let optionRequest = 0

function options(rows: Option[], selected: string[]) {
  const result = rows.map(row => ({
    label: row.enable
      ? displayName(row.name, row.nameEn)
      : `${displayName(row.name, row.nameEn)} · ${t('停用')}`,
    value: row.code,
    disabled: !row.enable && !selected.includes(row.code),
  }))
  for (const code of selected.filter(code => !rows.some(row => row.code === code)))
    result.push({ label: code, value: code, disabled: false })
  return result
}

const typeOptions = computed(() => options(props.metadata.types, [form.value.dataType]))
const formatOptions = computed(() => options(props.metadata.formats, form.value.textFormat
  ? [form.value.textFormat]
  : []))
const characters = computed(() => selectedCharacters(form.value))
const characterOptions = computed(() => options(props.metadata.characters, characters.value))
const stringDefault = computed(() => typeof form.value.defaultValue === 'string'
  ? form.value.defaultValue
  : null)
const multiDefault = computed(() => Array.isArray(form.value.defaultValue)
  ? form.value.defaultValue
  : [])
const booleanDefault = computed(() => typeof form.value.defaultValue === 'boolean'
  ? String(form.value.defaultValue)
  : null)
const booleanOptions = computed(() => [
  {
    label: t('真'),
    value: 'true',
  },
  {
    label: t('假'),
    value: 'false',
  },
])
const dictionaryOptions = computed(() => dictionaries.value.map(row => ({
  label: `${displayName(row.name, row.nameEn)} · ${row.code}${row.enable
    ? ''
    : ` · ${t('停用')}`}`,
  value: row.id,
  disabled: !row.enable && row.id !== form.value.paramGroupId,
})))
const enumOptions = computed(() => {
  const dictionary = dictionaries.value.find(row => row.id === form.value.paramGroupId)
  const selected = stringDefault.value === null
    ? multiDefault.value
    : [stringDefault.value]
  return options(values.value.map(row => ({ ...row, enable: row.enable && dictionary?.enable === true })), selected)
})

const canSave = computed(() => /^[a-z][a-z0-9_]{0,63}$/.test(form.value.code)
  && form.value.name.trim() !== '' && form.value.nameEn.trim() !== ''
  && (!form.value.textCharLimitEnabled || characters.value.length > 0)
  && (!isEnum(form.value.dataType) || form.value.paramGroupId !== null))

function selectType(type: string) {
  if (isDataType(type))
    form.value = changeFieldType(form.value, type)
}

function toggleCharacterLimit(enabled: boolean) {
  form.value.textCharLimitEnabled = enabled
  if (!enabled)
    setCharacters(form.value, [])
}

function changeMultiDefault(value: string[] | null) {
  form.value.defaultValue = value?.length ? value : null
}

function changeGroup(id: number | null) {
  if (form.value.paramGroupId !== id) {
    form.value.paramGroupId = id
    form.value.defaultValue = null
  }
}

async function loadGroups(keyword = '') {
  const request = ++groupRequest
  loadingGroups.value = true
  groupsFailed.value = false
  try {
    const { data } = await api.dictionaries(keyword, form.value.paramGroupId)
    if (request === groupRequest)
      dictionaries.value = data
  }
  catch {
    if (request === groupRequest)
      groupsFailed.value = true
  }
  finally {
    if (request === groupRequest)
      loadingGroups.value = false
  }
}

const searchGroups = useDebounceFn((keyword: string) => {
  if (isEnum(form.value.dataType))
    return loadGroups(keyword)
}, 250)

async function loadValues(id: number | null) {
  const request = ++optionRequest
  values.value = []
  optionsFailed.value = false
  loadingOptions.value = id !== null
  if (id === null)
    return
  try {
    const { data } = await api.options(id)
    if (request === optionRequest)
      values.value = data
  }
  catch {
    if (request === optionRequest)
      optionsFailed.value = true
  }
  finally {
    if (request === optionRequest)
      loadingOptions.value = false
  }
}

watch(() => isEnum(form.value.dataType), (active) => {
  if (active)
    void loadGroups()
  else
    groupRequest++
}, { immediate: true })
watch(() => form.value.paramGroupId, loadValues, { immediate: true })

function retryDictionaries() {
  if (groupsFailed.value)
    void loadGroups()
  if (optionsFailed.value)
    void loadValues(form.value.paramGroupId)
}

function close() {
  if (!saving.value)
    emit('close')
}

async function save() {
  if (saving.value || !canSave.value)
    return
  saving.value = true
  try {
    const data = {
      ...form.value,
      name: form.value.name.trim(),
      nameEn: form.value.nameEn.trim(),
    }
    if (props.initial)
      await api.update(props.initial.id, data)
    else
      await api.create(data)
    emit('saved')
  }
  catch {
    // 统一请求处理器展示服务端错误，保留已输入的配置。
  }
  finally {
    saving.value = false
  }
}
</script>

<style scoped>
.field-editor-modal {
  width: min(880px, 94vw);
  max-height: 90vh;
  overflow: auto;
}

.field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 16px;
}

.field-editor :deep(.n-input-number),
.field-editor :deep(.n-date-picker) {
  width: 100%;
}

@media (max-width: 800px) {
  .field-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
