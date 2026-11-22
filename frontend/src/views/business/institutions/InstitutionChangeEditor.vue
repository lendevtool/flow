<template>
  <n-drawer
    :show="true"
    width="min(660px, 100vw)"
    :aria-label="t('发起变更申请')"
    :mask-closable="!submitting"
    :close-on-esc="!submitting"
    @update:show="!$event && close()"
  >
    <n-drawer-content :title="t('发起变更申请')" :closable="!submitting">
      <p class="mb-16">
        {{ record.code }} · {{ displayName(record.nameZh, record.nameEn) }}
      </p>
      <n-form
        ref="formRef"
        :model="form"
        label-placement="left"
        label-align="left"
        label-width="clamp(96px, 28vw, 152px)"
        :disabled="submitting"
        @submit.prevent="submit"
      >
        <n-form-item :label="t('拟变更字段')" path="fields" :rule="selectionRule">
          <n-checkbox-group :value="form.fields" @update:value="selectFields">
            <n-space>
              <n-checkbox
                v-for="field in configuredFields"
                :key="field.code"
                :value="field.code"
                :label="fieldLabel(options, field)"
                :disabled="'options' in field && !available[field.options].length"
              />
            </n-space>
          </n-checkbox-group>
        </n-form-item>
        <n-form-item
          v-for="field in selectedFields"
          :key="field.code"
          :label="t('拟改{0}', fieldLabel(options, field))"
          :path="`values.${field.code}`"
          :rule="changeRule(field)"
        >
          <div class="min-w-0 w-full">
            <n-select
              v-if="'options' in field"
              v-model:value="form.values[field.code]"
              :aria-label="t('拟改{0}', fieldLabel(options, field))"
              :options="available[field.options]"
              filterable
            />
            <n-input
              v-else
              :value="form.values[field.code] ?? ''"
              :input-props="{ 'aria-label': t('拟改{0}', fieldLabel(options, field)) }"
              :maxlength="fieldLength(options, field)"
              :placeholder="isRequired(field) ? undefined : t('留空以清空')"
              @update:value="form.values[field.code] = $event"
            />
            <n-text depth="3" class="mt-6 block break-words text-sm">
              {{ t('当前{0}：{1}', fieldLabel(options, field), approvalValueLabel(options, field, approvalValue(options, record, field))) }}
            </n-text>
          </div>
        </n-form-item>
        <n-form-item :label="reasonLabel" path="reason" :rule="reasonRule">
          <n-input v-model:value="form.reason" type="textarea" :maxlength="fieldLength(options, changeReasonField)" :input-props="{ 'aria-label': reasonLabel }" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space>
          <NButton :disabled="submitting" @click="close">
            {{ t('取消') }}
          </NButton>
          <NButton type="primary" :loading="submitting" @click="submit">
            {{ t('提交审批') }}
          </NButton>
        </n-space>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { FormInst, FormItemRule } from 'naive-ui'
import type { Institution, InstitutionChange, InstitutionOptions } from './api'
import type { ApprovalCode, ApprovalField, ChangeDraft } from './model'
import { NButton } from 'naive-ui'
import { computed, reactive, ref } from 'vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import api from './api'
import { approvalSelectOptions, approvalValue, approvalValueLabel, availableApprovalFields, changeReasonField, changeWrite, fieldLabel, fieldLength } from './model'

const props = defineProps<{
  record: Institution
  options: InstitutionOptions
}>()
const emit = defineEmits<{
  close: []
  submitted: [change: InstitutionChange]
}>()

const formRef = ref<FormInst>()
const submitting = ref(false)
const form = reactive<{ fields: ApprovalCode[], values: ChangeDraft, reason: string }>({ fields: [], values: {}, reason: '' })
const reasonLabel = computed(() => fieldLabel(props.options, changeReasonField))
const selectionRule = computed<FormItemRule>(() => ({
  required: true,
  type: 'array',
  min: 1,
  trigger: 'change',
  message: t('请选择至少一个拟变更字段'),
}))
const reasonRule = computed<FormItemRule>(() => ({
  required: true,
  whitespace: true,
  trigger: ['blur', 'input'],
  message: t('请输入{0}', reasonLabel.value),
}))
const configuredFields = computed(() => availableApprovalFields(props.options))
const selectedFields = computed(() => configuredFields.value.filter(field => form.fields.includes(field.code)))
const available = computed(() => ({
  types: approvalSelectOptions(props.options.types, props.options.approvalOptions?.types),
  countries: approvalSelectOptions(props.options.countries, props.options.approvalOptions?.countries),
}))

function selectFields(codes: (string | number)[]) {
  form.fields = configuredFields.value.filter(field => codes.includes(field.code)).map((field) => {
    if (!Object.hasOwn(form.values, field.code)) {
      const current = approvalValue(props.options, props.record, field)
      form.values[field.code] = 'options' in field && !available.value[field.options].some(option => option.value === current)
        ? null
        : current
    }
    return field.code
  })
}

function isRequired(field: ApprovalField): boolean {
  return field.required || props.options.fieldDefinitions?.[field.code]?.required === true
}

function changeRule(field: ApprovalField): FormItemRule {
  const required = isRequired(field)
  return {
    required,
    trigger: ['blur', 'change', 'input'],
    validator: () => {
      const value = form.values[field.code]?.trim() || null
      if (required && value === null)
        return new Error(t('请填写拟改值'))
      if (value === approvalValue(props.options, props.record, field))
        return new Error(t('拟改值须与当前值不同'))
      return true
    },
  }
}

function close() {
  if (!submitting.value)
    emit('close')
}

async function submit() {
  if (submitting.value || !formRef.value)
    return
  submitting.value = true
  try {
    await formRef.value.validate()
    const { data } = await api.submit(props.record.id, changeWrite(props.record.version, form.reason, form.fields, form.values))
    emit('submitted', data)
  }
  catch {
    // 表单和接口拦截器分别展示对应错误。
  }
  finally {
    submitting.value = false
  }
}
</script>
