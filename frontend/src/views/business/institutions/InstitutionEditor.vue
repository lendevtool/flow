<template>
  <n-drawer
    :show="true"
    width="min(660px, 100vw)"
    :aria-label="title"
    :mask-closable="!saving && !reloading"
    :close-on-esc="!saving && !reloading"
    @update:show="!$event && close()"
  >
    <n-drawer-content :title="title" :closable="!saving && !reloading">
      <n-alert v-if="stale" type="warning" class="mb-16">
        {{ t('机构档案已更新。重新载入后会替换当前表单。') }}
        <NButton text :loading="reloading" @click="reload">
          {{ t('重新载入档案') }}
        </NButton>
      </n-alert>
      <n-form
        ref="formRef"
        :model="form"
        label-placement="left"
        label-align="left"
        label-width="clamp(96px, 28vw, 152px)"
        :disabled="saving || reloading"
        @submit.prevent="save"
      >
        <n-form-item
          v-for="field in fields"
          :key="field.key"
          :path="field.key"
          :label="fieldLabel(options, field)"
          :rule="fieldRule(field)"
        >
          <n-select
            v-if="'options' in field"
            v-model:value="form[field.key]"
            :aria-label="fieldLabel(options, field)"
            :options="institutionSelectOptions(options[field.options], record?.[field.key] ?? null)"
            filterable
          />
          <n-input
            v-else
            v-model:value="form[field.key]"
            :type="field.key === 'remark' ? 'textarea' : 'text'"
            :maxlength="fieldLength(options, field)"
            :input-props="{ 'aria-label': fieldLabel(options, field) }"
            :disabled="field.key === 'code' && !!record"
            :placeholder="fieldRule(field) ? undefined : t('选填')"
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space>
          <NButton :disabled="saving || reloading" @click="close">
            {{ t('取消') }}
          </NButton>
          <NButton type="primary" :loading="saving" :disabled="stale || reloading" @click="save">
            {{ t('保存') }}
          </NButton>
        </n-space>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { FormInst, FormItemRule } from 'naive-ui'
import type { Institution, InstitutionOptions } from './api'
import { NButton } from 'naive-ui'
import { computed, onBeforeUnmount, ref } from 'vue'
import { t } from '@/i18n'
import api from './api'
import { fieldLabel, fieldLength, institutionDraft, institutionEnums, institutionSelectOptions, institutionTexts, institutionWrite } from './model'

const props = defineProps<{
  initial: Institution | null
  options: InstitutionOptions
}>()
const emit = defineEmits<{
  close: []
  saved: [created: boolean]
}>()

const fields = [
  ...institutionTexts.slice(0, 3),
  ...institutionEnums,
  ...institutionTexts.slice(3),
]
const formRef = ref<FormInst>()
const record = ref(props.initial)
const form = ref(institutionDraft(record.value))
const saving = ref(false)
const stale = ref(false)
const reloading = ref(false)
const title = computed(() => t(record.value ? '编辑机构' : '新增机构'))
let reloadRequest: AbortController | undefined

function fieldRule(field: (typeof fields)[number]): FormItemRule | undefined {
  const label = fieldLabel(props.options, field)
  if ('options' in field) {
    return {
      required: true,
      type: 'number',
      trigger: 'change',
      message: t('请选择{0}', label),
    }
  }
  if (field.key !== 'code' && field.key !== 'nameZh' && !props.options.fieldDefinitions?.[field.code]?.required)
    return undefined
  return {
    required: true,
    whitespace: true,
    trigger: ['blur', 'input'],
    message: t('请输入{0}', label),
  }
}

function close() {
  if (!saving.value && !reloading.value)
    emit('close')
}

async function save() {
  if (saving.value || stale.value || reloading.value || !formRef.value)
    return
  saving.value = true
  try {
    await formRef.value.validate()
    const initial = record.value
    const data = institutionWrite(form.value, initial?.version)
    if (initial)
      await api.update(initial.id, data)
    else
      await api.create(data)
    emit('saved', initial === null)
  }
  catch (error) {
    if (record.value && error && typeof error === 'object' && 'code' in error && error.code === 409)
      stale.value = true
  }
  finally {
    saving.value = false
  }
}

async function reload() {
  if (!record.value || reloading.value)
    return
  const controller = new AbortController()
  reloadRequest = controller
  reloading.value = true
  try {
    const { data } = await api.detail(record.value.id, controller.signal)
    if (!controller.signal.aborted) {
      record.value = data
      form.value = institutionDraft(data)
      stale.value = false
    }
  }
  catch {
    if (!controller.signal.aborted)
      window.$message.error(t('加载失败，请重试'))
  }
  finally {
    if (!controller.signal.aborted)
      reloading.value = false
  }
}

onBeforeUnmount(() => reloadRequest?.abort())
</script>
