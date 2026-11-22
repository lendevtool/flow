<template>
  <n-drawer :show="true" width="min(820px, 100vw)" :mask-closable="!saving" :close-on-esc="!saving" @update:show="close">
    <n-drawer-content :title="t(initial ? '编辑邮件模板' : '新增邮件模板')" :closable="!saving">
      <n-form :disabled="saving" label-placement="top">
        <div class="template-grid">
          <n-form-item :label="t('编码')" required>
            <n-input v-model:value="form.code" :maxlength="64" />
          </n-form-item>
          <n-form-item :label="t('名称')" required>
            <n-input v-model:value="form.name" :maxlength="100" />
          </n-form-item>
          <n-form-item :label="t('发件邮箱')" required>
            <n-select v-model:value="form.accountId" :options="accountOptions" :loading="accountsLoading" filterable />
          </n-form-item>
          <n-form-item :label="t('发送方式')" required>
            <n-select v-model:value="form.sendMode" :options="modeOptions" />
          </n-form-item>
        </div>
        <n-alert v-if="accountsFailed" type="error" class="mb-12">
          {{ t('邮箱配置加载失败') }}
          <n-button text @click="emit('retryAccounts')">
            {{ t('重试') }}
          </n-button>
        </n-alert>
        <n-alert v-else-if="!accountsLoading && !initial && !accounts.some(account => account.enable)" type="warning" class="mb-12">
          {{ t('请先配置并启用发件邮箱。') }}
        </n-alert>
        <div class="mb-12 flex items-center gap-12">
          <n-switch v-model:value="form.enable" :aria-label="t('启用')" />
          <span>{{ t(form.enable ? '启用' : '停用') }}</span>
        </div>

        <n-form-item :label="t('邮件主题')" required>
          <n-input ref="subject" v-model:value="form.subjectTemplate" :maxlength="maxSubject" :input-props="{ 'aria-label': t('邮件主题') }">
            <template #suffix>
              <VariableInsert :names="insertOptions" :disabled="saving" @insert="insertSubject" />
            </template>
          </n-input>
        </n-form-item>

        <n-alert v-if="invalidVariable" type="warning" class="mb-12">
          {{ t('当前模板无法使用变量 {0}。', invalidVariable) }}
        </n-alert>
        <n-form-item :label="t('邮件正文')" required>
          <MarkdownEditor
            v-model="form.bodyTemplate"
            :label="t('邮件正文')"
            :maxlength="20000"
            :rows="8"
            :disabled="saving"
            allow-link
          >
            <template #tools="{ insertText }">
              <VariableInsert :names="insertOptions" @insert="insertText" />
            </template>
            <template #preview>
              <div v-if="sampleNames.length || needsRecipient" class="template-grid">
                <n-form-item v-for="name in sampleNames" :key="name" :label="name">
                  <n-input v-model:value="samples[name]" :placeholder="t('输入样例值')" :input-props="{ 'aria-label': name }" />
                </n-form-item>
                <n-form-item v-if="variables.includes('recipient.email')" :label="t('样例收件人地址')">
                  <n-input v-model:value="recipient.address" :maxlength="254" />
                </n-form-item>
                <n-form-item v-if="variables.includes('recipient.name')" :label="t('样例收件人姓名')">
                  <n-input v-model:value="recipient.name" :maxlength="200" />
                </n-form-item>
              </div>
              <n-button size="small" type="primary" secondary :disabled="saving || !validContent" :loading="previewLoading" @click="renderPreview">
                {{ t('生成预览') }}
              </n-button>
              <n-alert v-if="previewError" type="error" class="mt-12">
                {{ previewError }}
              </n-alert>
              <div v-if="preview" class="mt-12">
                <n-text strong>
                  {{ preview.subject }}
                </n-text>
                <iframe class="mail-preview-frame" :title="t('邮件正文预览')" sandbox="" :srcdoc="preview.html" />
              </div>
            </template>
          </MarkdownEditor>
        </n-form-item>
      </n-form>

      <template #footer>
        <n-space justify="end">
          <n-button :disabled="saving" @click="close">
            {{ t('取消') }}
          </n-button>
          <n-button type="primary" :loading="saving" :disabled="!complete" @click="requestSave">
            {{ t('保存') }}
          </n-button>
        </n-space>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { InputInst } from 'naive-ui'
import type { MailOption, MailPreview, MailTemplate, SendMode, TemplateWrite } from './api'
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import MarkdownEditor from '@/components/common/MarkdownEditor.vue'
import { t } from '@/i18n'
import api from './api'
import { isMailCode, sendModeLabels } from './presentation'
import { discoverVariables, isVariableName, recipientVariables } from './templateVariables'
import VariableInsert from './VariableInsert.vue'

const props = defineProps<{
  initial: MailTemplate | null
  accounts: MailOption[]
  accountsLoading: boolean
  accountsFailed: boolean
}>()
const emit = defineEmits<{ close: [], saved: [], retryAccounts: [] }>()
const maxSubject = 500
const initial = props.initial
const form = reactive({
  code: initial?.code ?? '',
  name: initial?.name ?? '',
  enable: initial?.enable ?? true,
  accountId: initial?.accountId ?? null as number | null,
  subjectTemplate: initial?.subjectTemplate ?? '',
  bodyTemplate: initial?.bodyTemplate ?? '',
  sendMode: initial?.sendMode ?? 'INDIVIDUAL' as SendMode,
})
const subject = ref<InputInst>()
const saving = ref(false)
const preview = ref<MailPreview>()
const previewError = ref('')
const previewLoading = ref(false)
const samples = reactive<Record<string, string>>(Object.create(null))
const recipient = reactive({ address: '', name: '' })
let previewRequest: AbortController | undefined

const accountOptions = computed(() => props.accounts.map(row => ({
  value: row.id,
  label: `${row.name} · ${row.code}${row.enable
    ? ''
    : ` · ${t('停用')}`}`,
  disabled: !row.enable && row.id !== initial?.accountId,
})))
const modeOptions = computed(() => Object.entries(sendModeLabels).map(([value, label]) => ({
  value,
  label: t(label),
})))
const variables = computed(() => discoverVariables(form.subjectTemplate, form.bodyTemplate))
const sampleNames = computed(() => variables.value.filter(name => !recipientVariables.includes(name)))
const needsRecipient = computed(() => variables.value.some(name => recipientVariables.includes(name)))
const insertOptions = computed(() => [
  ...new Set([
    ...sampleNames.value,
    ...(form.sendMode === 'INDIVIDUAL'
      ? recipientVariables
      : []),
  ]),
])
const invalidVariable = computed(() => variables.value.find(name => !isVariableName(name)
  || (name.startsWith('recipient.') && (form.sendMode === 'TOGETHER' || !recipientVariables.includes(name)))))
const validContent = computed(() => !!form.subjectTemplate.trim() && !!form.bodyTemplate.trim() && !invalidVariable.value)
const complete = computed(() => isMailCode(form.code.trim()) && !!form.name.trim() && form.accountId !== null
  && !props.accountsFailed && !props.accountsLoading && validContent.value)

function insertSubject(text: string) {
  const input = subject.value?.inputElRef
  if (!input)
    return
  const start = input.selectionStart ?? form.subjectTemplate.length
  const end = input.selectionEnd ?? start
  const content = form.subjectTemplate.slice(0, start) + text + form.subjectTemplate.slice(end)
  if (content.length > maxSubject)
    return
  form.subjectTemplate = content
  void nextTick(() => {
    input.focus()
    input.setSelectionRange(start + text.length, start + text.length)
  })
}

function clearPreview() {
  previewRequest?.abort()
  previewRequest = undefined
  previewLoading.value = false
  preview.value = undefined
  previewError.value = ''
}

watch([
  () => form.subjectTemplate,
  () => form.bodyTemplate,
  () => form.sendMode,
  samples,
  recipient,
], clearPreview)
onBeforeUnmount(clearPreview)

async function renderPreview() {
  if (!validContent.value || previewLoading.value)
    return
  clearPreview()
  const controller = new AbortController()
  previewRequest = controller
  previewLoading.value = true
  try {
    const { data } = await api.preview({
      subjectTemplate: form.subjectTemplate,
      bodyTemplate: form.bodyTemplate,
      sendMode: form.sendMode,
      variables: Object.fromEntries(sampleNames.value
        .filter(name => Object.hasOwn(samples, name))
        .map(name => [name, samples[name]])),
      recipient: needsRecipient.value
        ? {
            address: variables.value.includes('recipient.email')
              ? recipient.address.trim() || undefined
              : undefined,
            name: variables.value.includes('recipient.name')
              ? recipient.name.trim() || undefined
              : undefined,
          }
        : undefined,
    }, controller.signal)
    if (previewRequest === controller)
      preview.value = data
  }
  catch (error) {
    if (previewRequest === controller) {
      previewError.value = error && typeof error === 'object' && 'message' in error && typeof error.message === 'string'
        ? error.message
        : t('预览失败')
    }
  }
  finally {
    if (previewRequest === controller) {
      previewRequest = undefined
      previewLoading.value = false
    }
  }
}

function close() {
  if (!saving.value)
    emit('close')
}

function requestSave() {
  if (saving.value || !complete.value)
    return
  if (initial?.enable && !form.enable) {
    window.$dialog.warning({
      title: t('停用邮件模板'),
      content: t('停用模板「{0}」后，无法使用该模板发送邮件。', initial.name),
      positiveText: t('停用'),
      negativeText: t('取消'),
      onPositiveClick: save,
    })
    return
  }
  void save()
}

async function save() {
  if (saving.value || !complete.value || form.accountId === null)
    return
  const data: TemplateWrite = {
    ...form,
    accountId: form.accountId,
    name: form.name.trim(),
    code: form.code.trim().toUpperCase(),
  }
  saving.value = true
  try {
    if (initial)
      await api.updateTemplate(initial.id, data)
    else
      await api.createTemplate(data)
    emit('saved')
  }
  catch {
    // 请求处理器显示服务端错误，保留本次输入以便修正。
  }
  finally {
    saving.value = false
  }
}
</script>

<style scoped>
.template-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 16px;
}

.mail-preview-frame {
  display: block;
  width: 100%;
  min-height: 180px;
  margin-top: 8px;
  border: 1px solid #8885;
  border-radius: 4px;
  background: #fff;
}

@media (max-width: 600px) {
  .template-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
