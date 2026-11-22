<template>
  <n-drawer
    :show="true"
    width="min(660px, 100vw)"
    :aria-label="title"
    :mask-closable="!saving"
    :close-on-esc="!saving"
    @update:show="!$event && close()"
  >
    <n-drawer-content :title="title" :closable="!saving">
      <n-alert v-if="loadFailed" type="error" class="mb-16">
        {{ t('来源接口加载失败') }}
        <n-button text @click="loadSources">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-empty v-else-if="!loading && !sources.length" :description="t('暂无可导入的只读接口')" class="my-20" />
      <n-form
        v-else
        ref="formRef"
        :model="form"
        :rules="rules"
        :disabled="saving"
        label-placement="left"
        label-align="left"
        label-width="clamp(100px, 26vw, 142px)"
        @submit.prevent="save"
      >
        <n-form-item :label="t('来源接口')" path="sourceKey">
          <n-select
            :value="form.sourceKey"
            :loading="loading"
            :disabled="loading"
            :options="sourceOptions"
            :placeholder="t('按路径或方法查找 REST 接口')"
            filterable
            @update:value="selectSource"
          />
        </n-form-item>
        <template v-if="source">
          <n-form-item :label="t('接口名称')" path="displayName">
            <n-input v-model:value="form.displayName" :maxlength="128" />
          </n-form-item>
          <n-form-item label="Scope" path="endpointCode">
            <n-input v-model:value="form.endpointCode" placeholder="calendar.status.read" :maxlength="96" />
          </n-form-item>
          <n-form-item :label="t('对外路径后缀')" path="slug">
            <div class="w-full">
              <n-input v-model:value="form.slug" placeholder="trading-calendar-status" :maxlength="managedEndpointSlugMax" />
              <code class="mt-6 block break-all">{{ source.httpMethod }} {{ publicPath }}</code>
            </div>
          </n-form-item>
          <n-form-item v-if="!source.wholeResponse" :label="t('响应字段')" path="responseFields">
            <n-checkbox-group v-model:value="form.responseFields" class="flex flex-col items-start gap-8">
              <n-checkbox v-for="field in source.fields" :key="field.name" :value="field.name">
                <code>{{ field.name }}</code>
                <span class="ml-8">{{ field.format || field.type }}</span>
              </n-checkbox>
            </n-checkbox-group>
          </n-form-item>
          <EndpointContract
            :parameters="source.parameters"
            :collection="source.collection"
            :page="source.page"
            :whole-response="source.wholeResponse"
          />
        </template>
      </n-form>
      <template #footer>
        <n-space>
          <n-button :disabled="saving" @click="close">
            {{ t('取消') }}
          </n-button>
          <n-button type="primary" :disabled="!source || loading || loadFailed" :loading="saving" @click="save">
            {{ t('导入接口') }}
          </n-button>
        </n-space>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { FormInst, FormRules } from 'naive-ui'
import type { EndpointImportDraft } from './model'
import type { Endpoint, EndpointSource } from '@/api/open-api/api'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import api from '@/api/open-api/api'
import { t } from '@/i18n'
import EndpointContract from './EndpointContract.vue'
import { endpointCodePattern, endpointImportPayload, endpointModuleLabel, managedEndpointPrefix, managedEndpointSlugMax, managedEndpointSlugPattern } from './model'

const emit = defineEmits<{ close: [], saved: [endpoint: Endpoint] }>()
const title = computed(() => t('从现有接口导入'))
const form = reactive<EndpointImportDraft & { sourceKey: string | null }>({
  sourceKey: null,
  displayName: '',
  endpointCode: '',
  slug: '',
  responseFields: [],
})
const formRef = ref<FormInst>()
const sources = ref<EndpointSource[]>([])
const loading = ref(true)
const loadFailed = ref(false)
const saving = ref(false)
let sourceRequest: AbortController | undefined

const sourceKey = (item: EndpointSource) => `${item.httpMethod} ${item.sourcePath}`
const source = computed(() => sources.value.find(item => sourceKey(item) === form.sourceKey))
const sourceOptions = computed(() => sources.value.map(item => ({
  label: `${sourceKey(item)} · ${endpointModuleLabel(item.moduleCode)}`,
  value: sourceKey(item),
})))
const publicPath = computed(() => `${managedEndpointPrefix}${form.slug.trim() || '{slug}'}`)
const rules = computed<FormRules>(() => ({
  sourceKey: {
    required: true,
    message: t('选择已部署的只读接口'),
    trigger: 'change',
  },
  displayName: {
    required: true,
    whitespace: true,
    message: t('请输入接口名称'),
    trigger: 'blur',
  },
  endpointCode: {
    required: true,
    validator: () => endpointCodePattern.test(form.endpointCode.trim()) || new Error(t('Scope 须以小写字母或数字开头，可包含点、下划线和连字符。')),
    trigger: 'blur',
  },
  slug: {
    required: true,
    validator: () => managedEndpointSlugPattern.test(form.slug.trim()) || new Error(t('路径后缀仅允许小写字母、数字及分隔用的连字符。')),
    trigger: 'blur',
  },
  responseFields: {
    required: true,
    type: 'array',
    min: 1,
    message: t('请选择至少一个响应字段'),
    trigger: 'change',
  },
}))

async function loadSources() {
  const request = new AbortController()
  sourceRequest?.abort()
  sourceRequest = request
  loading.value = true
  loadFailed.value = false
  try {
    const { data } = await api.endpointSources({ importable: true }, request.signal)
    if (!request.signal.aborted)
      sources.value = data.filter(item => item.importable)
  }
  catch {
    if (!request.signal.aborted)
      loadFailed.value = true
  }
  finally {
    if (!request.signal.aborted)
      loading.value = false
  }
}

function selectSource(key: string | null) {
  form.sourceKey = key
  form.responseFields = []
  formRef.value?.restoreValidation()
}

async function save() {
  if (saving.value || !source.value || !formRef.value)
    return
  saving.value = true
  try {
    await formRef.value.validate()
    const payload = endpointImportPayload(source.value, form)
    if (payload) {
      const { data } = await api.importEndpoint(payload)
      emit('saved', data)
    }
  }
  catch {
    // 表单展示校验错误，接口错误由统一请求层展示。
  }
  finally {
    saving.value = false
  }
}

function close() {
  if (!saving.value)
    emit('close')
}

onMounted(loadSources)
onBeforeUnmount(() => sourceRequest?.abort())
</script>
