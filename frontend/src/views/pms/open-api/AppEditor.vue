<template>
  <n-drawer
    :show="true"
    width="min(580px, 100vw)"
    :aria-label="title"
    :mask-closable="!busy"
    :close-on-esc="!busy"
    @update:show="!$event && close()"
  >
    <n-drawer-content :title="title" :closable="!busy">
      <n-alert v-if="stale" type="warning" class="mb-16">
        {{ t('应用配置已变更，重新加载将替换当前表单。') }}
        <n-button text :loading="reloading" @click="reload">
          {{ t('重新加载') }}
        </n-button>
      </n-alert>
      <n-form
        ref="formRef"
        :model="form"
        :disabled="busy"
        label-placement="left"
        label-align="left"
        label-width="clamp(98px, 26vw, 134px)"
        @submit.prevent="save"
      >
        <n-form-item :label="t('应用名称')" path="name" :rule="nameRule">
          <n-input v-model:value="form.name" :maxlength="128" />
        </n-form-item>
        <n-form-item :label="t('负责人')" path="ownerUserId">
          <n-select
            v-model:value="form.ownerUserId"
            :options="ownerOptions"
            :loading="ownerLoading"
            :placeholder="t('搜索启用的用户')"
            clearable
            filterable
            remote
            @search="searchOwners"
            @scroll="moreOwners"
          />
        </n-form-item>
        <n-alert v-if="ownerFailed" type="error" class="mb-12">
          {{ t('负责人列表加载失败') }}
          <n-button text @click="loadOwners(true)">
            {{ t('重试') }}
          </n-button>
        </n-alert>
        <n-form-item :label="t('用途')" path="description">
          <n-input v-model:value="form.description" type="textarea" :maxlength="512" show-count />
        </n-form-item>
        <n-form-item :label="t('应用配额（次）')" path="rateLimitCount" :rule="quotaRule">
          <n-input-number v-model:value="form.rateLimitCount" :min="1" :max="maxQuota" :precision="0" class="w-full" />
        </n-form-item>
        <n-form-item :label="t('时间窗口（秒）')" path="rateLimitWindowSeconds" :rule="quotaRule">
          <n-input-number v-model:value="form.rateLimitWindowSeconds" :min="1" :max="maxQuota" :precision="0" class="w-full" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space>
          <n-button :disabled="busy" @click="close">
            {{ t('取消') }}
          </n-button>
          <n-button type="primary" :loading="saving" :disabled="busy || stale" @click="save">
            {{ t('保存') }}
          </n-button>
        </n-space>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { FormInst, FormItemRule } from 'naive-ui'
import type { App, AppWrite, OwnerOption } from '@/api/open-api/api'
import { useDebounceFn } from '@vueuse/core'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import api from '@/api/open-api/api'
import { t } from '@/i18n'
import { appPatch, isConflict, maxQuota } from './model'

const props = defineProps<{ initial: App | null }>()
const emit = defineEmits<{ close: [], saved: [app: App, created: boolean] }>()
const record = ref(props.initial)
const formRef = ref<FormInst>()
const form = ref(draft(record.value))
const saving = ref(false)
const reloading = ref(false)
const stale = ref(false)
const busy = computed(() => saving.value || reloading.value)
const title = computed(() => t(record.value ? '编辑应用' : '新增应用'))
const nameRule = computed<FormItemRule>(() => ({
  required: true,
  whitespace: true,
  message: t('请输入应用名称'),
  trigger: 'blur',
}))
const quotaRule = computed<FormItemRule>(() => ({
  required: true,
  type: 'number',
  message: t('请输入正整数'),
  trigger: 'change',
}))

let unmounted = false
const owners = ref<OwnerOption[]>([])
const selectedOwner = ref<OwnerOption | null>(originalOwner(record.value))
const ownerLoading = ref(false)
const ownerFailed = ref(false)
const ownerSearch = ref('')
const ownerTotal = ref(0)
let ownerPage = 0
let ownerRequest: AbortController | undefined
let reloadRequest: AbortController | undefined
const ownerOptions = computed(() => {
  const options = owners.value.map(owner => ({ value: owner.id, label: owner.displayName }))
  const selected = selectedOwner.value
  if (selected && selected.id === form.value.ownerUserId && !options.some(option => option.value === selected.id))
    options.unshift({ value: selected.id, label: selected.displayName })
  return options
})
const debouncedOwners = useDebounceFn(() => loadOwners(true), 250)

function draft(app: App | null): {
  name: string
  ownerUserId: number | null
  description: string
  rateLimitCount: number | null
  rateLimitWindowSeconds: number | null
} {
  return {
    name: app?.name ?? '',
    ownerUserId: app?.ownerUserId ?? null,
    description: app?.description ?? '',
    rateLimitCount: app?.rateLimitCount ?? 600,
    rateLimitWindowSeconds: app?.rateLimitWindowSeconds ?? 60,
  }
}

function originalOwner(app: App | null): OwnerOption | null {
  return app?.ownerUserId == null
    ? null
    : {
        id: app.ownerUserId,
        displayName: app.ownerDisplayName ?? String(app.ownerUserId),
      }
}

function searchOwners(keyword: string) {
  const chosen = owners.value.find(owner => owner.id === form.value.ownerUserId)
  if (chosen)
    selectedOwner.value = chosen
  ownerSearch.value = keyword.trim()
  ownerRequest?.abort()
  ownerLoading.value = true
  void debouncedOwners()
}

async function loadOwners(reset: boolean) {
  if (unmounted)
    return
  const request = new AbortController()
  ownerRequest?.abort()
  ownerRequest = request
  ownerLoading.value = true
  ownerFailed.value = false
  const page = reset
    ? 1
    : ownerPage + 1
  try {
    const { data } = await api.owners({ pageNo: page, pageSize: 20, keyword: ownerSearch.value || undefined }, request.signal)
    if (!request.signal.aborted) {
      owners.value = reset
        ? data.pageData
        : [
            ...owners.value,
            ...data.pageData,
          ]
      ownerTotal.value = data.total
      ownerPage = page
    }
  }
  catch {
    if (!request.signal.aborted)
      ownerFailed.value = true
  }
  finally {
    if (!request.signal.aborted)
      ownerLoading.value = false
  }
}

function moreOwners(event: Event) {
  const box = event.target
  if (box instanceof HTMLElement && !ownerLoading.value && !ownerFailed.value && owners.value.length < ownerTotal.value
    && box.scrollTop + box.clientHeight >= box.scrollHeight - 12) {
    void loadOwners(false)
  }
}

function close() {
  if (!busy.value)
    emit('close')
}

async function save() {
  if (busy.value || stale.value || !formRef.value)
    return
  saving.value = true
  try {
    await formRef.value.validate()
    const { name, description, ownerUserId, rateLimitCount, rateLimitWindowSeconds } = form.value
    if (rateLimitCount === null || rateLimitWindowSeconds === null)
      return
    const payload: AppWrite = {
      name: name.trim(),
      description: description === record.value?.description
        ? description
        : description.trim() || null,
      ownerUserId,
      rateLimitCount,
      rateLimitWindowSeconds,
    }
    const app = record.value
    const { data } = app
      ? await api.updateApp(app.id, appPatch(app, payload))
      : await api.createApp(payload)
    emit('saved', data, app === null)
  }
  catch (error) {
    if (record.value && isConflict(error))
      stale.value = true
  }
  finally {
    saving.value = false
  }
}

async function reload() {
  const app = record.value
  if (!app || busy.value)
    return
  const request = new AbortController()
  reloadRequest = request
  reloading.value = true
  try {
    const { data } = await api.app(app.id, request.signal)
    if (!request.signal.aborted) {
      record.value = data
      selectedOwner.value = originalOwner(data)
      form.value = draft(data)
      stale.value = false
    }
  }
  catch {
    // 统一请求提示已处理加载错误。
  }
  finally {
    reloading.value = false
  }
}

onMounted(() => loadOwners(true))
onBeforeUnmount(() => {
  unmounted = true
  ownerRequest?.abort()
  reloadRequest?.abort()
})
</script>
