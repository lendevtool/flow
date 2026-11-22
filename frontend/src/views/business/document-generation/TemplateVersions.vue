<template>
  <n-drawer :show="true" width="min(960px, 100vw)" :mask-closable="!busy" :close-on-esc="!busy" @update:show="show => !show && close()">
    <n-drawer-content :title="t('版本管理')" :closable="!busy">
      <div class="mb-12 flex flex-wrap items-center justify-between gap-8">
        <h3 class="m-0 break-words">
          {{ template.name }}
        </h3>
        <n-button type="primary" :loading="operation === 'create'" :disabled="busy && operation !== 'create'" @click="newVersion()">
          {{ t('新建草稿') }}
        </n-button>
      </div>
      <n-alert v-if="listError" type="error" class="mb-12">
        {{ listError }}
        <n-button text @click="loadVersions">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-data-table :data="versions" :columns="columns" :row-key="row => row.id" size="small" :scroll-x="560" :loading="loading">
        <template #empty>
          <n-empty v-if="!loading && !listError" :description="t('暂无可用版本')" />
        </template>
      </n-data-table>

      <n-alert v-if="error" type="error" class="docgen-version-error mt-16" role="alert">
        {{ error }}
        <n-button v-if="requestedVersion && (conflict || version === null)" text :disabled="busy" @click="select(requestedVersion)">
          {{ t('重新载入版本') }}
        </n-button>
      </n-alert>
      <n-spin :show="operation === 'load'" class="mt-20">
        <template v-if="version">
          <div class="mb-12 flex flex-wrap items-center justify-between gap-8">
            <h3 class="m-0">
              {{ t('版本 {0}', version.versionNo) }} · {{ statusLabel(version.status) }}
            </h3>
            <n-button v-if="version.documentVersionId" :loading="operation === 'file'" :disabled="busy && operation !== 'file'" @click="downloadTemplate">
              {{ t('下载模板文件') }}
            </n-button>
          </div>
          <n-form label-placement="left" label-align="left" :label-width="112" :disabled="!draft || busy" @submit.prevent="save">
            <n-form-item v-for="field in configFields" :key="field.key" :label="field.label">
              <n-input v-model:value="config[field.key]" :input-props="{ 'aria-label': field.label, 'spellcheck': false }" type="textarea" :autosize="{ minRows: 3, maxRows: 14 }" class="font-mono" />
            </n-form-item>
            <n-space v-if="draft" justify="end">
              <n-button type="primary" attr-type="submit" :loading="operation === 'save'" :disabled="!dirty || (busy && operation !== 'save')">
                {{ t('保存配置') }}
              </n-button>
            </n-space>
          </n-form>
          <n-divider v-if="draft" />
          <n-form v-if="draft" label-placement="left" label-align="left" :label-width="112" :disabled="busy" @submit.prevent="upload">
            <n-form-item :label="t('模板文件')">
              <n-upload v-model:file-list="files" accept=".docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document" :default-upload="false" :max="1" :disabled="busy">
                <n-button :disabled="busy">
                  {{ t('选择 Word 文件') }}
                </n-button>
              </n-upload>
            </n-form-item>
            <n-space justify="end">
              <n-button attr-type="submit" :loading="operation === 'upload'" :disabled="!fileToUpload || (busy && operation !== 'upload')">
                {{ t('上传模板') }}
              </n-button>
            </n-space>
          </n-form>
          <n-alert v-if="draft && dirty" type="info" class="mt-12">
            {{ t('保存配置后可试生成或发布。') }}
          </n-alert>
          <n-space v-if="draft" justify="end" class="mt-16">
            <n-button :disabled="busy || dirty || !version.documentVersionId" @click="openTest">
              {{ t('试生成') }}
            </n-button>
            <n-button v-if="canPublish" type="primary" :loading="operation === 'publish'" :disabled="(busy && operation !== 'publish') || dirty || !version.documentVersionId" @click="confirmPublish">
              {{ t('发布') }}
            </n-button>
          </n-space>
        </template>
      </n-spin>
    </n-drawer-content>
  </n-drawer>

  <n-drawer :show="testing" placement="left" width="min(740px, 100vw)" :aria-label="t('试生成')" :mask-closable="false" :close-on-esc="operation !== 'test'" @update:show="show => !show && operation !== 'test' && (testing = false)">
    <n-drawer-content :title="t('试生成')" :closable="operation !== 'test'">
      <n-form id="docgen-test-form" :disabled="operation === 'test'" @submit.prevent="test">
        <n-space v-if="testSamples.length" class="mb-12">
          <n-select v-model:value="testSampleIndex" :options="testSamples" :input-props="{ 'aria-label': t('模板样例') }" :placeholder="t('请选择样例')" style="width: 220px" />
          <n-button :disabled="testSampleIndex === null || operation === 'test'" @click="applyTestSample">
            {{ t('载入样例') }}
          </n-button>
        </n-space>
        <n-input v-model:value="testInput" :input-props="{ 'aria-label': t('输入 JSON'), 'spellcheck': false }" type="textarea" :autosize="{ minRows: 10, maxRows: 20 }" class="font-mono" />
        <n-alert v-if="testError" type="error" class="docgen-version-error mt-12" role="alert">
          {{ testError }}
        </n-alert>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button :disabled="operation === 'test'" @click="testing = false">
            {{ t('关闭') }}
          </n-button>
          <n-button type="primary" attr-type="submit" form="docgen-test-form" :loading="operation === 'test'">
            {{ t('试生成') }}
          </n-button>
        </n-space>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { DataTableColumns, UploadFileInfo } from 'naive-ui'
import type { DocxFile, TemplateSample, TemplateSummary, TemplateVersion } from './api'
import { NTag } from 'naive-ui'
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue'
import TableActions from '@/components/common/TableActions.vue'
import { t } from '@/i18n'
import { formatDateTime } from '@/utils'
import api, { errorCode, errorText } from './api'
import { createValidator, formatJson, isJsonObject, parseJsonObject } from './input'
import { statusLabel } from './schema'

const props = defineProps<{ template: TemplateSummary, initialVersion?: TemplateVersion, canPublish: boolean }>()
const emit = defineEmits<{ close: [], changed: [], preview: [file: DocxFile], download: [file: DocxFile] }>()
const versions = ref<TemplateVersion[]>([])
const version = ref<TemplateVersion | null>(null)
const config = ref(configText())
const requestedVersion = ref<string>()
const currentVersionId = ref(props.template.currentVersionId)
const operation = ref<'load' | 'create' | 'save' | 'upload' | 'file' | 'test' | 'publish' | null>(null)
const busy = computed(() => operation.value !== null)
const loading = ref(false)
const listError = ref('')
const error = ref('')
const conflict = ref(false)
const files = ref<UploadFileInfo[]>([])
const fileToUpload = computed(() => files.value[0]?.file)
const draft = computed(() => version.value?.status === 'DRAFT')
const dirty = computed(() => JSON.stringify(config.value) !== JSON.stringify(configText(version.value ?? undefined)))
const testing = ref(false)
const testInput = ref('{}')
const testError = ref('')
const testSampleIndex = ref<number | null>(null)
const testSamples = computed(() => [
  ...(version.value?.exampleJson
    ? [
        {
          label: t('默认样例'),
          value: 0,
        },
      ]
    : []),
  ...(version.value?.samples ?? []).map((sample, index) => ({ label: sample.name, value: index + 1 })),
])
let listRequest: AbortController | undefined
let versionRequest: AbortController | undefined

const configFields = computed(() => [
  {
    key: 'schema',
    label: t('输入 Schema'),
  },
  {
    key: 'example',
    label: t('默认样例 JSON'),
  },
  {
    key: 'samples',
    label: t('具名样例 JSON'),
  },
  {
    key: 'renderer',
    label: t('渲染配置 JSON'),
  },
] as const)
const columns = computed<DataTableColumns<TemplateVersion>>(() => [
  {
    key: 'versionNo',
    title: t('版本'),
    width: 80,
  },
  {
    key: 'status',
    title: t('状态'),
    minWidth: 120,
    render: row => h(NTag, {
      size: 'small',
      type: row.status === 'PUBLISHED'
        ? 'success'
        : 'default',
    }, () =>
      row.id === currentVersionId.value
        ? t('当前发布版本')
        : statusLabel(row.status)),
  },
  {
    key: 'updatedAt',
    title: t('更新时间'),
    width: 174,
    render: row => formatDateTime(row.updatedAt),
  },
  {
    key: 'actions',
    title: t('操作'),
    width: 192,
    fixed: 'right',
    render: row => h(TableActions, {
      actions: [
        {
          key: 'select',
          label: t(row.status === 'DRAFT'
            ? '编辑草稿'
            : '查看'),
          disabled: busy.value,
          onClick: () => select(row.id),
        },
        ...(row.status === 'PUBLISHED'
          ? [
              {
                key: 'copy',
                label: t('复制为草稿'),
                disabled: busy.value,
                onClick: () => newVersion(row.id),
              },
            ]
          : []),
      ],
    }),
  },
])

function configText(value?: TemplateVersion) {
  return {
    schema: formatJson(value?.inputSchema ?? { type: 'object' }),
    example: value?.exampleJson
      ? formatJson(value.exampleJson)
      : '',
    renderer: value?.renderConfig
      ? formatJson(value.renderConfig)
      : '',
    samples: formatJson((value?.samples ?? []).map(sample => ({ ...sample }))),
  }
}

function confirmDiscard(action: () => void) {
  if (!dirty.value) {
    action()
    return
  }
  window.$dialog.warning({
    title: t('放弃未保存的配置'),
    content: t('继续将清除当前草稿中尚未保存的配置。'),
    positiveText: t('放弃'),
    negativeText: t('取消'),
    onPositiveClick: action,
  })
}

function close() {
  if (!busy.value)
    confirmDiscard(() => emit('close'))
}

async function loadVersions() {
  listRequest?.abort()
  const controller = new AbortController()
  listRequest = controller
  loading.value = true
  listError.value = ''
  try {
    const { data } = await api.versions(props.template.id, controller.signal)
    if (!controller.signal.aborted)
      versions.value = data
  }
  catch (failure) {
    if (!controller.signal.aborted)
      listError.value = errorText(failure, t('版本列表加载失败'))
  }
  finally {
    if (!controller.signal.aborted)
      loading.value = false
  }
}

function applyVersion(value: TemplateVersion, reset = true) {
  version.value = value
  requestedVersion.value = value.id
  conflict.value = false
  if (reset) {
    config.value = configText(value)
    files.value = []
  }
}

function select(id: string) {
  if (!busy.value)
    confirmDiscard(() => { void loadVersion(id) })
}

async function loadVersion(id: string) {
  requestedVersion.value = id
  versionRequest?.abort()
  const controller = new AbortController()
  versionRequest = controller
  operation.value = 'load'
  error.value = ''
  conflict.value = false
  try {
    const { data } = await api.version(props.template.id, id, controller.signal)
    if (!controller.signal.aborted)
      applyVersion(data)
  }
  catch (failure) {
    if (!controller.signal.aborted) {
      error.value = errorText(failure, t('版本加载失败'))
      conflict.value = true
    }
  }
  finally {
    if (!controller.signal.aborted)
      operation.value = null
  }
}

function newVersion(source?: string) {
  if (!busy.value)
    confirmDiscard(() => { void create(source) })
}

async function create(source?: string) {
  operation.value = 'create'
  error.value = ''
  try {
    const { data } = await api.createVersion(props.template.id, source)
    applyVersion(data)
    void loadVersions()
  }
  catch (failure) {
    error.value = errorText(failure, t('新建草稿失败'))
  }
  finally {
    operation.value = null
  }
}

function parseSamples(): TemplateSample[] {
  const values = parseJsonObject(`{"samples": ${config.value.samples.trim() || '[]'}}`).samples
  if (!Array.isArray(values))
    throw new TypeError(t('具名样例应为包含 code、name 和 input 的数组'))
  return values.map((sample) => {
    if (!isJsonObject(sample) || typeof sample.code !== 'string' || typeof sample.name !== 'string' || !isJsonObject(sample.input))
      throw new TypeError(t('具名样例应为包含 code、name 和 input 的数组'))
    return { code: sample.code, name: sample.name, input: sample.input }
  })
}

function fail(failure: unknown, fallback: string) {
  error.value = errorText(failure, fallback)
  conflict.value = errorCode(failure) === 409
}

async function save() {
  if (busy.value || !version.value || !draft.value)
    return
  operation.value = 'save'
  error.value = ''
  try {
    const inputSchema = parseJsonObject(config.value.schema)
    createValidator(inputSchema)
    const { data } = await api.saveVersion(version.value, {
      inputSchema,
      exampleJson: config.value.example.trim()
        ? parseJsonObject(config.value.example)
        : null,
      renderConfig: config.value.renderer.trim()
        ? parseJsonObject(config.value.renderer)
        : null,
      samples: parseSamples(),
    })
    applyVersion(data)
    void loadVersions()
    window.$message.success(t('配置已保存'))
  }
  catch (failure) {
    fail(failure, t('保存失败'))
  }
  finally {
    operation.value = null
  }
}

async function upload() {
  const file = fileToUpload.value
  if (busy.value || !version.value || !draft.value || !file)
    return
  if (!file.name.toLowerCase().endsWith('.docx')) {
    error.value = t('请选择 .docx 文件')
    return
  }

  operation.value = 'upload'
  error.value = ''
  try {
    const { data } = await api.upload(version.value, file)
    applyVersion(data, false)
    files.value = []
    void loadVersions()
    window.$message.success(t('模板文件已上传'))
  }
  catch (failure) {
    fail(failure, t('上传失败'))
  }
  finally {
    operation.value = null
  }
}

async function downloadTemplate() {
  if (!version.value?.documentVersionId || busy.value)
    return
  operation.value = 'file'
  error.value = ''
  try {
    emit('download', await api.templateFile(version.value))
  }
  catch (failure) {
    error.value = errorText(failure, t('Word 文件读取失败'))
  }
  finally {
    operation.value = null
  }
}

function openTest() {
  testInput.value = '{}'
  testSampleIndex.value = null
  testError.value = ''
  testing.value = true
}

function applyTestSample() {
  if (!version.value || testSampleIndex.value === null)
    return
  const value = testSampleIndex.value === 0
    ? version.value.exampleJson
    : version.value.samples[testSampleIndex.value - 1]?.input
  if (value) {
    testInput.value = formatJson(value)
    testError.value = ''
  }
}

async function test() {
  if (busy.value || !version.value || !draft.value || dirty.value)
    return
  testError.value = ''
  operation.value = 'test'
  try {
    const input = parseJsonObject(testInput.value)
    const issues = createValidator(version.value.inputSchema)(input)
    if (issues.length) {
      testError.value = issues.map(issue => `${issue.path} · ${issue.message}`).join('\n')
      return
    }
    const file = await api.testVersion(version.value, input)
    testing.value = false
    emit('preview', file)
  }
  catch (failure) {
    testError.value = errorText(failure, t('试生成失败'))
    if (errorCode(failure) === 409)
      fail(failure, t('试生成失败'))
  }
  finally {
    operation.value = null
  }
}

function confirmPublish() {
  if (!version.value || !props.canPublish || !draft.value || busy.value || dirty.value)
    return
  window.$dialog.warning({
    title: t('发布模板版本'),
    content: t('发布 v{0} 后，使用此模板的新文档将按该版本生成。', version.value.versionNo),
    positiveText: t('发布'),
    negativeText: t('取消'),
    onPositiveClick: () => publish(),
  })
}

async function publish() {
  if (!version.value || !props.canPublish || !draft.value || busy.value || dirty.value)
    return
  operation.value = 'publish'
  error.value = ''
  try {
    const { data } = await api.publish(version.value)
    applyVersion(data)
    currentVersionId.value = data.id
    void loadVersions()
    emit('changed')
    window.$message.success(t('模板版本已发布'))
  }
  catch (failure) {
    fail(failure, t('发布失败'))
  }
  finally {
    operation.value = null
  }
}

onMounted(() => {
  if (props.initialVersion)
    applyVersion(props.initialVersion)
  void loadVersions()
})
onBeforeUnmount(() => {
  listRequest?.abort()
  versionRequest?.abort()
})
</script>

<style scoped>
.docgen-version-error {
  white-space: pre-wrap;
}
</style>
