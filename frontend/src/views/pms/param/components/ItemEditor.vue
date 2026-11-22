<template>
  <n-modal v-model:show="show" preset="card" :title="form.id ? t('编辑参数明细') : t('新建参数明细')" :style="{ width: '880px', maxWidth: '94vw' }" :mask-closable="!saving" :close-on-esc="!saving" :closable="!saving">
    <div class="mb-16 flex items-center gap-8 text-13 opacity-70">
      <i class="i-fe:folder" />{{ displayName(group.name, group.nameEn) }} <span> / {{ group.code }}</span>
    </div>
    <n-form ref="formRef" :model="form" :rules="rules" label-placement="top" class="item-form">
      <div class="form-grid">
        <n-form-item :label="t('编码')" path="code">
          <n-input v-model:value="form.code" :disabled="!!form.id" :maxlength="64" :placeholder="t('字典选项值或配置编码')" />
        </n-form-item>
        <n-form-item :label="t('名称')" path="name">
          <n-input v-model:value="form.name" :maxlength="100" :placeholder="t('字典显示文本或配置名称')" />
        </n-form-item>
        <n-form-item :label="t('英文名称')" path="nameEn">
          <n-input v-model:value="form.nameEn" :maxlength="100" :placeholder="t('选填')" />
        </n-form-item>
        <n-form-item :label="t('序号')" path="sort">
          <n-input-number v-model:value="form.sort" :min="0" :max="2147483647" :precision="0" class="w-full" />
        </n-form-item>
        <n-form-item :label="t('状态')">
          <n-switch v-model:value="form.enable">
            <template #checked>
              {{ t('启用') }}
            </template><template #unchecked>
              {{ t('停用') }}
            </template>
          </n-switch>
        </n-form-item>
      </div>
      <n-divider title-placement="left">
        {{ t('固定字段') }}
      </n-divider>
      <div class="form-grid">
        <n-form-item v-for="field in fixedFields" :key="field.key" :label="workbenchSlot && field.key === 'value1' ? t('待办选项字典') : field.label" :path="field.key" :rule="fieldRule(field)">
          <n-select
            v-if="workbenchSlot && field.key === 'value1'"
            v-model:value="form.value1"
            :options="dictionaryOptions"
            :loading="dictionaryLoading"
            :disabled="!!form.extra.bindingLocked"
            filterable clearable
            :placeholder="t('请选择待办专用参数分类')"
          />
          <FieldInput v-else v-model:value="form[field.key]" :field="field" />
        </n-form-item>
      </div>
      <n-alert v-if="workbenchSlot && !dictionaryLoading && !dictionaryOptions.length" type="info" class="mb-12">
        {{ t('请先新建编码以 WORKBENCH_TODO_DICT_ 开头的参数分类。') }}
      </n-alert>
      <template v-if="extraFields.length">
        <n-divider title-placement="left">
          {{ t('扩展字段') }}
        </n-divider>
        <div class="form-grid">
          <n-form-item v-for="field in extraFields" :key="field.key" :label="field.label" :path="`extra.${field.key}`" :rule="fieldRule(field)">
            <FieldInput v-model:value="form.extra[field.key]" :field="field" />
          </n-form-item>
        </div>
      </template>
      <n-collapse class="mb-20">
        <n-collapse-item :title="t('其他扩展属性（JSON）')" name="json">
          <template #header-extra>
            <CopyButton :text="otherJson" />
          </template>
          <n-input v-model:value="otherJson" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" placeholder="{}" />
          <div class="mt-6 text-12 opacity-60">
            {{ t('保存未定义为表单字段的属性；已配置字段请在上方填写。') }}
          </div>
        </n-collapse-item>
      </n-collapse>
      <n-form-item :label="t('备注')">
        <n-input v-model:value="form.remark" type="textarea" :maxlength="500" show-count />
      </n-form-item>
    </n-form>
    <template #footer>
      <div class="flex justify-end gap-12">
        <n-button :disabled="saving" @click="show = false">
          {{ t('取消') }}
        </n-button>
        <n-button type="primary" :loading="saving" @click="save">
          {{ t('保存') }}
        </n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup>
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import api from '../api'
import { applyDefaults, fieldDefinitions, itemPayload, toPlain } from '../fields'
import FieldInput from './FieldInput.vue'

const emit = defineEmits(['saved'])
const show = ref(false)
const saving = ref(false)
const formRef = ref(null)
const group = ref({})
const form = ref({ extra: {} })
const otherJson = ref('{}')
const workbenchSlot = computed(() => group.value.code === 'WORKBENCH_TODO_DIMENSION')
const dictionaryOptions = ref([])
const dictionaryLoading = ref(false)
let dictionaryLoad = 0
const fields = computed(() => fieldDefinitions(group.value.fieldConfig))
const fixedFields = computed(() => fields.value.filter(field => field.storage === 'fixed'))
const extraFields = computed(() => fields.value.filter(field => field.storage === 'extra'))
const rules = computed(() => ({
  code: [
    {
      required: true,
      message: t('请输入编码'),
      trigger: 'blur',
    },
    {
      pattern: /^[A-Z0-9][\w.:-]{0,63}$/i,
      message: t('编码只允许字母、数字和_.:-'),
      trigger: 'blur',
    },
  ],
  name: {
    required: true,
    whitespace: true,
    message: t('请输入名称'),
    trigger: 'blur',
  },
  sort: {
    required: true,
    type: 'number',
    message: t('请输入序号'),
    trigger: 'blur',
  },
}))
function fieldRule(field) {
  return {
    required: !!field.required,
    trigger: ['blur', 'change'],
    validator: (_, value) => {
      if (field.required && (value === null || value === undefined || String(value).trim() === ''))
        return new Error(t('请填写{0}', field.label))
      return true
    },
  }
}
function open(category, record) {
  group.value = toPlain(category)
  form.value = record
    ? toPlain(record)
    : applyDefaults(category.fieldConfig)
  form.value.extra ||= {}
  const keys = new Set(extraFields.value.map(field => field.key))
  otherJson.value = JSON.stringify(Object.fromEntries(Object.entries(form.value.extra).filter(([key]) => !keys.has(key))), null, 2)
  show.value = true
  dictionaryOptions.value = []
  dictionaryLoading.value = false
  dictionaryLoad++
  if (workbenchSlot.value)
    loadWorkbenchDictionaries(dictionaryLoad)
  nextTick(() => formRef.value?.restoreValidation())
}
async function loadWorkbenchDictionaries(sequence) {
  dictionaryLoading.value = true
  try {
    const { data } = await api.groups({ keyword: 'WORKBENCH_TODO_DICT_', pageNo: 1, pageSize: 100 })
    if (sequence !== dictionaryLoad)
      return
    dictionaryOptions.value = data.pageData
      .filter(category => category.code.startsWith('WORKBENCH_TODO_DICT_'))
      .map(category => ({
        label: displayName(category.name, category.nameEn),
        value: String(category.id),
      }))
  }
  catch (error) {
    if (sequence === dictionaryLoad)
      $message.error(error.message || t('分类加载失败，请重试'))
  }
  finally {
    if (sequence === dictionaryLoad)
      dictionaryLoading.value = false
  }
}
async function save() {
  if (saving.value)
    return
  try {
    await formRef.value.validate()
  }
  catch {
    return
  }
  let extra
  try {
    extra = JSON.parse(otherJson.value || '{}')
    if (!extra || typeof extra !== 'object' || Array.isArray(extra))
      throw new Error(t('扩展属性必须为 JSON 对象'))
    for (const field of extraFields.value)
      extra[field.key] = form.value.extra[field.key] ?? null
  }
  catch (error) {
    $message.error(t('扩展属性格式错误：{0}', error.message))
    return
  }
  saving.value = true
  try {
    const payload = itemPayload({
      ...form.value,
      name: form.value.name.trim(),
      extra,
    })
    if (form.value.id)
      await api.updateItem(group.value.id, form.value.id, payload)
    else
      await api.createItem(group.value.id, payload)
    show.value = false
    $message.success(t('保存成功'))
    emit('saved', group.value.id)
  }
  catch { /* Request client displays write errors. */ }
  finally { saving.value = false }
}
defineExpose({ open })
</script>

<style scoped>
.item-form {
  max-height: 65vh;
  overflow-y: auto;
  padding-right: 8px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
}
@media (max-width: 600px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
