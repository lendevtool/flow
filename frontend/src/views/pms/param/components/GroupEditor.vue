<template>
  <n-modal v-model:show="show" preset="card" :title="form.id ? t('编辑参数分类') : t('新建参数分类')" class="param-modal" :style="{ width: '800px', maxWidth: '94vw' }" :mask-closable="!saving" :close-on-esc="!saving" :closable="!saving">
    <n-form ref="formRef" :model="form" :rules="rules" label-placement="top">
      <n-tabs v-model:value="tab" type="line" animated>
        <n-tab-pane name="basic" :tab="t('基本信息')" display-directive="show">
          <n-form-item :label="t('参数编码')" path="code">
            <n-input v-model:value="form.code" :disabled="!!form.id" :maxlength="64" :placeholder="t('如 fund_status、app_page')" />
          </n-form-item>
          <n-form-item :label="t('参数名称')" path="name">
            <n-input v-model:value="form.name" :maxlength="100" :placeholder="t('请输入参数名称')" />
          </n-form-item>
          <n-form-item :label="t('英文名称')" path="nameEn">
            <n-input v-model:value="form.nameEn" :maxlength="100" :placeholder="t('选填')" />
          </n-form-item>
          <div class="grid grid-cols-2 gap-16">
            <n-form-item :label="t('排序')" path="sort">
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
          <n-form-item :label="t('备注')">
            <n-input v-model:value="form.remark" type="textarea" :maxlength="500" show-count />
          </n-form-item>
        </n-tab-pane>
        <n-tab-pane name="fields" :tab="t('字段配置')" display-directive="show">
          <FieldConfigEditor v-if="show" :key="editorKey" ref="fieldEditor" :value="form.fieldConfig" />
        </n-tab-pane>
      </n-tabs>
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
import api from '../api'
import { toPlain } from '../fields'
import FieldConfigEditor from './FieldConfigEditor.vue'

const emit = defineEmits(['saved'])
const show = ref(false)
const saving = ref(false)
const tab = ref('basic')
const form = ref({})
const formRef = ref(null)
const fieldEditor = ref(null)
const editorKey = ref(0)
const rules = computed(() => ({
  code: [
    {
      required: true,
      message: t('请输入参数编码'),
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
    message: t('请输入参数名称'),
    trigger: 'blur',
  },
  sort: {
    required: true,
    type: 'number',
    message: t('请输入排序'),
    trigger: 'blur',
  },
}))
function open(record) {
  form.value = record
    ? toPlain(record)
    : {
        code: '',
        name: '',
        enable: true,
        sort: 0,
        remark: '',
        fieldConfig: {},
      }
  form.value.fieldConfig ||= {}
  editorKey.value++
  tab.value = 'basic'
  show.value = true
  nextTick(() => formRef.value?.restoreValidation())
}
async function save() {
  if (saving.value)
    return
  try {
    await formRef.value.validate()
  }
  catch {
    tab.value = 'basic'
    return
  }
  let config
  try {
    config = fieldEditor.value?.getConfig() || form.value.fieldConfig
  }
  catch (error) {
    tab.value = 'fields'
    $message.error(error.message)
    return
  }
  saving.value = true
  try {
    const { code, name, nameEn, enable, sort, remark } = form.value
    const payload = {
      code,
      name: name.trim(),
      nameEn: nameEn ?? null,
      enable,
      sort,
      remark,
      fieldConfig: config,
    }
    const { data } = form.value.id
      ? await api.updateGroup(form.value.id, payload)
      : await api.createGroup(payload)
    show.value = false
    $message.success(t('保存成功'))
    emit('saved', data, !form.value.id)
  }
  catch { /* Request client displays write errors. */ }
  finally { saving.value = false }
}
defineExpose({ open })
</script>

<style scoped>
:deep(.n-tab-pane) {
  max-height: 60vh;
  overflow-y: auto;
  padding-right: 8px;
}
</style>
