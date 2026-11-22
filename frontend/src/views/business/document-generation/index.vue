<template>
  <CommonPage :title="t('文档生成')">
    <n-tabs v-model:value="activeTab" type="line" animated>
      <n-tab-pane name="generate" :tab="t('文档生成')">
        <n-alert v-if="listError" type="error" class="mb-16">
          {{ listError }}
          <n-button text @click="loadTemplates">
            {{ t('重试') }}
          </n-button>
        </n-alert>
        <n-form :label-width="108" label-placement="left" label-align="left" :disabled="working" class="docgen-input" @submit.prevent>
          <n-form-item :label="t('模板')">
            <n-select
              :value="selectedId"
              :options="options"
              :loading="loadingTemplates || loadingCurrent"
              :input-props="{ 'aria-label': t('模板') }"
              :placeholder="t('请选择模板')"
              filterable
              @update:value="chooseTemplate"
            />
          </n-form-item>
          <n-empty v-if="!loadingTemplates && !listError && !templates.length && !detached" :description="t('暂无已启用的发布模板')" />
          <n-alert v-if="templateError" type="error" class="mb-16">
            {{ templateError }}
            <n-button v-if="selectedId" text @click="retryCurrent?.()">
              {{ t('重试') }}
            </n-button>
          </n-alert>
          <n-form-item v-if="current || detached" :show-feedback="false">
            <n-button :disabled="working" @click="inputOpen = true">
              {{ t('编辑输入') }}
            </n-button>
          </n-form-item>
        </n-form>
      </n-tab-pane>
      <n-tab-pane name="history" :tab="t('生成记录')" display-directive="show:lazy">
        <GenerationHistory
          ref="history"
          :templates="templates"
          :can-run="canRun"
          :can-read-file="canReadFile"
          :restoring="restoring"
          :file-busy="fileBusy"
          @restore="restore"
          @file="openFile"
        />
      </n-tab-pane>
      <n-tab-pane v-if="canManage" name="templates" :tab="t('模板管理')" display-directive="show:lazy">
        <TemplateAdmin :can-publish="canPublish" @changed="loadTemplates" @preview="preview = $event" @download="download" />
      </n-tab-pane>
    </n-tabs>

    <n-drawer
      :show="inputOpen"
      placement="left"
      width="min(980px, 100vw)"
      :aria-label="t('编辑输入')"
      :mask-closable="!working"
      :close-on-esc="!working"
      @update:show="show => !show && !working && (inputOpen = false)"
    >
      <n-drawer-content :title="t('编辑输入')" :closable="!working">
        <n-alert v-if="templateError" type="error" class="mb-16">
          {{ templateError }}
          <n-button v-if="selectedId" text @click="retryCurrent?.()">
            {{ t('重试') }}
          </n-button>
        </n-alert>
        <n-form :label-width="108" label-placement="left" label-align="left" :disabled="working" class="docgen-input" @submit.prevent="generate">
          <n-alert v-if="notice" type="info" class="mb-16">
            {{ notice }}
          </n-alert>
          <template v-if="(current || detached) && !loadingCurrent">
            <n-form-item v-if="current" :label="t('版本')" :show-feedback="false" class="mb-12">
              <span class="break-words">{{ current.name }} · v{{ current.versionNo }}<template v-if="selectedTemplate?.description"> · {{ selectedTemplate.description }}</template></span>
            </n-form-item>
            <n-form-item v-if="sampleOptions.length" :label="t('模板样例')">
              <div class="w-full flex items-center gap-8">
                <n-select v-model:value="sampleIndex" :options="sampleOptions" :input-props="{ 'aria-label': t('模板样例') }" :placeholder="t('请选择样例')" />
                <n-button :disabled="sampleIndex === null || working" @click="applySample">
                  {{ t('载入样例') }}
                </n-button>
              </div>
            </n-form-item>
            <n-radio-group v-if="current" :value="editor.state.mode" :aria-label="t('输入方式')" class="mb-16" :disabled="working" @update:value="switchMode">
              <n-radio-button value="form">
                {{ t('表单') }}
              </n-radio-button>
              <n-radio-button value="json">
                JSON
              </n-radio-button>
            </n-radio-group>
            <n-input
              v-if="editor.state.mode === 'json' || detached"
              :value="editor.state.text"
              type="textarea"
              :input-props="{ 'aria-label': t('输入 JSON'), 'spellcheck': false }"
              :autosize="{ minRows: 10, maxRows: 24 }"
              class="font-mono"
              @update:value="editText"
            />
            <SchemaField v-else-if="current" :schema="current.inputSchema" :root="current.inputSchema" :value="editor.state.object" :path="[]" @change="editField" />

            <n-alert v-if="showIssues && editor.state.issues.length" type="warning" class="mt-12" :title="t('请检查输入数据')">
              <ul class="m-0 list-disc pl-20">
                <li v-for="(issue, index) in editor.state.issues.slice(0, 20)" :key="index">
                  <code v-if="issue.path">{{ issue.path }} · </code>{{ issue.message }}
                </li>
              </ul>
              <p v-if="editor.state.issues.length > 20" class="mt-6">
                {{ t('共 {0} 处输入错误', editor.state.issues.length) }}
              </p>
            </n-alert>

            <n-space v-if="current && canRun" class="mt-16">
              <n-button type="primary" attr-type="submit" :loading="submitting" :disabled="(working && !submitting) || result?.status === 'RUNNING'">
                {{ t('生成 Word') }}
              </n-button>
            </n-space>
          </template>
        </n-form>
        <n-alert v-if="generationErrorText" type="error" class="docgen-result mt-16" role="alert">
          {{ generationErrorText }}
        </n-alert>
        <n-alert v-if="result" :type="resultType" :title="statusLabel(result.status)" class="docgen-result mt-16">
          <div>{{ result.templateName }} · v{{ result.versionNo }} · {{ formatDateTime(result.createdAt) }}</div>
          <div v-if="result.errorCode" class="mt-4">
            {{ generationError(result.errorCode) }}
          </div>
          <n-space v-if="result.status === 'RUNNING' || (canReadFile && result.hasFile)" class="mt-8">
            <n-button v-if="result.status === 'RUNNING'" size="small" :loading="checkingResult" @click="refreshResult">
              {{ t('刷新状态') }}
            </n-button>
            <template v-if="canReadFile && result.hasFile">
              <n-button size="small" :loading="fileBusy === result.id" @click="openFile(result, 'preview')">
                {{ t('预览') }}
              </n-button>
              <n-button size="small" :disabled="fileBusy !== null" @click="openFile(result, 'download')">
                {{ t('下载 Word') }}
              </n-button>
            </template>
          </n-space>
        </n-alert>
      </n-drawer-content>
    </n-drawer>

    <DocxPreview v-if="preview" :file="preview" @close="preview = null" @download="download" />
  </CommonPage>
</template>

<script setup lang="ts">
import type { CurrentTemplate, DocxFile, Generation, TemplateSummary } from './api'
import type { FieldPath, JsonObject, JsonValue } from './input'
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import CommonPage from '@/components/common/CommonPage.vue'
import { t } from '@/i18n'
import { useUserStore } from '@/store'
import { formatDateTime } from '@/utils'
import { downloadBlob } from '@/utils/downloadBlob'
import api, { errorCode, errorText, hasBusinessError } from './api'
import GenerationHistory from './GenerationHistory.vue'
import { createInputEditor, createSubmissionKeys, createValidator, updateJsonField } from './input'
import { generationError, statusLabel } from './schema'
import SchemaField from './SchemaField.vue'
import TemplateAdmin from './TemplateAdmin.vue'

defineOptions({ name: 'DocumentGenerationView' })
const DocxPreview = defineAsyncComponent(() => import('./DocxPreview.vue'))

const route = useRoute()
const user = useUserStore()
const canRun = computed(() => can('DocumentGenerationRun'))
const canReadFile = computed(() => can('DocumentGenerationReadFile'))
const canManage = computed(() => can('DocumentGenerationTemplateManage'))
const canPublish = computed(() => canManage.value && can('DocumentGenerationTemplatePublish'))
const activeTab = ref('generate')
const templates = ref<TemplateSummary[]>([])
const history = ref<InstanceType<typeof GenerationHistory>>()
const selectedId = ref<string | null>(null)
const current = ref<CurrentTemplate | null>(null)
const inputOpen = ref(false)
const detached = ref(false)
const loadingTemplates = ref(false)
const loadingCurrent = ref(false)
const listError = ref('')
const templateError = ref('')
const generationErrorText = ref('')
const notice = ref('')
const sampleIndex = ref<number | null>(null)
const showIssues = ref(false)
const submitting = ref(false)
const checkingResult = ref(false)
const result = ref<Generation | null>(null)
const restoring = ref<string | null>(null)
const fileBusy = ref<string | null>(null)
const preview = ref<DocxFile | null>(null)
const editor = createInputEditor()
const keys = createSubmissionKeys()
let listRequest: AbortController | undefined
let currentRequest: AbortController | undefined
let retryCurrent: (() => Promise<void>) | undefined
let restoreRequest: AbortController | undefined
let fileRequest: AbortController | undefined
let resultRequest: AbortController | undefined
let disposed = false

const working = computed(() => submitting.value || restoring.value !== null)
const options = computed(() => templates.value.map(row => ({ value: row.id, label: row.name })))
const selectedTemplate = computed(() => templates.value.find(row => row.id === selectedId.value))
const sampleOptions = computed(() => [
  ...(current.value?.exampleJson
    ? [
        {
          label: t('默认样例'),
          value: 0,
        },
      ]
    : []),
  ...(current.value?.samples ?? []).map((row, index) => ({ label: row.name, value: index + 1 })),
])
const resultType = computed(() => result.value?.status === 'SUCCEEDED'
  ? 'success'
  : result.value?.status === 'FAILED'
    ? 'error'
    : 'info')

function can(code: string) {
  const buttons = route.meta.btns
  return user.currentRole.code === 'SUPER_ADMIN'
    || (Array.isArray(buttons) && buttons.some(button => button.code === code))
}

function confirmReplace(action: () => void) {
  if (!editor.state.dirty && !detached.value) {
    action()
    return
  }
  window.$dialog.warning({
    title: t('覆盖当前输入'),
    content: t('继续将替换编辑器里的输入数据。'),
    positiveText: t('覆盖'),
    negativeText: t('取消'),
    onPositiveClick: action,
  })
}

async function loadTemplates() {
  listRequest?.abort()
  const controller = new AbortController()
  listRequest = controller
  loadingTemplates.value = true
  listError.value = ''
  try {
    const { data } = await api.templates(controller.signal)
    if (!controller.signal.aborted)
      templates.value = data
  }
  catch (error) {
    if (!controller.signal.aborted)
      listError.value = errorText(error, t('模板列表加载失败'))
  }
  finally {
    if (!controller.signal.aborted)
      loadingTemplates.value = false
  }
}

function chooseTemplate(id: string) {
  if (id !== selectedId.value && !working.value)
    confirmReplace(() => loadCurrent(id))
}

async function loadCurrent(id: string, input?: JsonObject) {
  currentRequest?.abort()
  const controller = new AbortController()
  currentRequest = controller
  loadingCurrent.value = true
  selectedId.value = id
  current.value = null
  retryCurrent = () => loadCurrent(id, input)
  templateError.value = ''
  try {
    const { data } = await api.currentTemplate(id, controller.signal)
    if (!controller.signal.aborted) {
      useCurrent(data, input)
      inputOpen.value = true
      if (input)
        notice.value = t('模板版本已更新，请核对输入后重新生成。')
    }
  }
  catch (error) {
    if (!controller.signal.aborted) {
      if (input && errorCode(error) === 404) {
        showDetached(input, t('模板当前不可用，已保留输入 JSON。'))
        void loadTemplates()
      }
      else {
        templateError.value = errorText(error, t('模板加载失败'))
      }
    }
  }
  finally {
    if (!controller.signal.aborted)
      loadingCurrent.value = false
  }
}

function useCurrent(template: CurrentTemplate, input?: JsonObject) {
  const validator = createValidator(template.inputSchema)
  current.value = template
  selectedId.value = template.templateId
  detached.value = false
  sampleIndex.value = null
  templateError.value = ''
  generationErrorText.value = ''
  notice.value = ''
  result.value = null
  showIssues.value = input !== undefined
  editor.useTemplate(validator, input)
}

function switchMode(mode: 'form' | 'json') {
  if (!editor.switchTo(mode))
    showIssues.value = true
}

function editText(value: string) {
  showIssues.value = true
  editor.setText(value)
}

function editField(path: FieldPath, value: JsonValue | undefined) {
  showIssues.value = true
  editor.setObject(updateJsonField(editor.state.object, path, value))
}

function applySample() {
  const template = current.value
  if (!template || sampleIndex.value === null)
    return
  const sample = sampleIndex.value === 0
    ? template.exampleJson
    : template.samples[sampleIndex.value - 1]?.input
  if (sample) {
    confirmReplace(() => {
      editor.replace(sample, () => true)
      showIssues.value = true
    })
  }
}

async function generate() {
  const version = current.value?.versionId
  if (!canRun.value || !version || working.value || loadingCurrent.value || result.value?.status === 'RUNNING')
    return
  showIssues.value = true
  const input = editor.read()
  if (!input)
    return

  submitting.value = true
  generationErrorText.value = ''
  result.value = null
  try {
    const response = await api.generate(version, input, keys.get(version, input))
    keys.finish(version, input)
    if (!disposed) {
      result.value = response.data
      void history.value?.refresh()
    }
  }
  catch (error) {
    const code = errorCode(error)
    if (typeof code === 'number' && code < 500)
      keys.finish(version, input)
    if (!disposed) {
      generationErrorText.value = errorText(error, t('生成请求失败'))
      if (hasBusinessError(error, 'TEMPLATE_VERSION_CHANGED') && selectedId.value)
        await loadCurrent(selectedId.value, input)
    }
  }
  finally {
    submitting.value = false
  }
}

async function refreshResult() {
  const id = result.value?.id
  if (!id || checkingResult.value)
    return
  resultRequest?.abort()
  const controller = new AbortController()
  resultRequest = controller
  checkingResult.value = true
  generationErrorText.value = ''
  try {
    const { data } = await api.generation(id, controller.signal)
    if (!controller.signal.aborted && result.value?.id === id)
      result.value = data
  }
  catch (error) {
    if (!controller.signal.aborted && result.value?.id === id)
      generationErrorText.value = errorText(error, t('加载失败'))
  }
  finally {
    if (!controller.signal.aborted)
      checkingResult.value = false
  }
}

function restore(row: Generation) {
  if (canRun.value && row.hasInput && !working.value)
    confirmReplace(() => restoreInput(row))
}

async function restoreInput(row: Generation) {
  currentRequest?.abort()
  loadingCurrent.value = false
  const controller = new AbortController()
  restoreRequest = controller
  restoring.value = row.id
  try {
    const { data: input } = await api.generationInput(row.id, controller.signal)
    const template = await api.currentTemplate(row.templateId, controller.signal).catch((error: unknown) => {
      if (errorCode(error) === 404)
        return null
      throw error
    })
    if (controller.signal.aborted)
      return
    if (template) {
      useCurrent(template.data, input)
      if (template.data.versionId !== row.templateVersionId)
        notice.value = t('已载入历史输入。请按当前发布版本核对数据。')
    }
    else {
      showDetached(input, t('原模板当前不可用，已载入历史 JSON。'))
    }
    history.value?.close()
    inputOpen.value = true
  }
  catch (error) {
    if (!controller.signal.aborted)
      window.$message.error(errorText(error, t('输入快照不可用或已过期')))
  }
  finally {
    restoring.value = null
  }
}

function showDetached(input: JsonObject, reason: string) {
  selectedId.value = null
  current.value = null
  detached.value = true
  sampleIndex.value = null
  templateError.value = ''
  generationErrorText.value = ''
  result.value = null
  editor.useTemplate(() => [], input)
  editor.switchTo('json')
  notice.value = reason
}

async function openFile(row: Generation, action: 'preview' | 'download') {
  if (!canReadFile.value || !row.hasFile || fileBusy.value !== null)
    return
  const controller = new AbortController()
  fileRequest = controller
  fileBusy.value = row.id
  try {
    const file = await api.generationFile(row.id, controller.signal)
    if (!controller.signal.aborted) {
      if (action === 'preview')
        preview.value = file
      else
        download(file)
    }
  }
  catch (error) {
    if (!controller.signal.aborted)
      window.$message.error(errorText(error, t('Word 文件读取失败')))
  }
  finally {
    fileBusy.value = null
  }
}

function download({ file, name }: DocxFile) {
  downloadBlob(file, name)
}

onMounted(loadTemplates)
onBeforeUnmount(() => {
  disposed = true
  listRequest?.abort()
  currentRequest?.abort()
  restoreRequest?.abort()
  fileRequest?.abort()
  resultRequest?.abort()
})
</script>

<style scoped>
.docgen-input,
.docgen-result {
  max-width: 980px;
  white-space: pre-wrap;
}

@media (max-width: 600px) {
  .docgen-input :deep(.n-form-item-label) {
    max-width: 80px;
  }
}
</style>
