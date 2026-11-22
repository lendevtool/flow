<template>
  <n-drawer
    :show="true"
    width="min(625px, 100vw)"
    :aria-label="title"
    :mask-closable="!saving"
    :close-on-esc="!saving"
    @update:show="!$event && close()"
  >
    <n-drawer-content :title="title" :closable="!saving">
      <n-alert v-if="stale" type="warning" class="mb-16">
        {{ t('接口或授权配置已变更。请关闭表单，从已刷新的列表重新打开。') }}
      </n-alert>
      <n-form
        label-placement="left"
        label-align="left"
        label-width="clamp(98px, 26vw, 146px)"
        :disabled="saving || stale"
        @submit.prevent="save"
      >
        <n-form-item :label="t('接口')" required>
          <div v-if="initial">
            {{ initial.displayName }}
            <code class="block break-words">{{ initial.endpointCode }}</code>
          </div>
          <n-select
            v-else
            :value="target?.endpointId ?? null"
            :options="endpointOptions"
            :loading="loadingEndpoints"
            :placeholder="t('搜索已发布接口')"
            filterable
            remote
            clearable
            @update:value="selectEndpoint"
            @search="searchEndpoints"
            @scroll="moreEndpoints"
          />
        </n-form-item>
        <n-alert v-if="endpointsFailed" type="error" class="mb-16">
          {{ t('接口目录加载失败') }}
          <n-button text @click="loadEndpoints(true)">
            {{ t('重试') }}
          </n-button>
        </n-alert>
        <n-form-item :label="t('接口配额（次）')" :validation-status="quotaMismatch ? 'error' : undefined">
          <n-input-number v-model:value="draft.limitCount" :min="1" :max="maxQuota" :precision="0" :placeholder="t('选填')" class="w-full" />
        </n-form-item>
        <n-form-item
          :label="t('时间窗口（秒）')"
          :validation-status="quotaMismatch ? 'error' : undefined"
          :feedback="quotaMismatch ? t('接口配额和时间窗口需同时填写。') : t('留空时仅使用应用配额。')"
        >
          <n-input-number v-model:value="draft.limitWindow" :min="1" :max="maxQuota" :precision="0" :placeholder="t('选填')" class="w-full" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space>
          <n-button :disabled="saving" @click="close">
            {{ t('取消') }}
          </n-button>
          <n-button type="primary" :loading="saving" :disabled="!canSave" @click="save">
            {{ t('保存') }}
          </n-button>
        </n-space>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { GrantDraft } from './model'
import type { Endpoint, Grant, GrantTarget } from '@/api/open-api/api'
import { useDebounceFn } from '@vueuse/core'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import api from '@/api/open-api/api'
import { t } from '@/i18n'
import { grantPayload, isConflict, maxQuota } from './model'

const props = defineProps<{ appId: number, initial: Grant | null, grants: readonly Grant[] }>()
const emit = defineEmits<{ close: [], saved: [], refresh: [] }>()
const record = ref(props.initial)
const target = ref<GrantTarget | null>(props.initial)
const saving = ref(false)
const stale = ref(false)
const draft = ref<GrantDraft>(initialDraft(props.initial))
const title = computed(() => t(props.initial?.status === 'GRANTED'
  ? '编辑授权'
  : '新增授权'))

const endpoints = ref<Endpoint[]>([])
const endpointSearch = ref('')
const loadingEndpoints = ref(false)
const endpointsFailed = ref(false)
const endpointTotal = ref(0)
let endpointPage = 0
let endpointRequest: AbortController | undefined
let unmounted = false
const debounceEndpoints = useDebounceFn(() => loadEndpoints(true), 250)

const endpointOptions = computed(() => {
  const granted = new Set(props.grants.filter(row => row.status === 'GRANTED').map(row => row.endpointId))
  const options = endpoints.value.map(row => ({
    value: row.id,
    label: `${row.displayName} · ${row.endpointCode}`,
    disabled: granted.has(row.id),
  }))
  const current = target.value
  if (current && !options.some(option => option.value === current.endpointId))
    options.unshift({ value: current.endpointId, label: `${current.displayName} · ${current.endpointCode}`, disabled: false })
  return options
})

const quotaMismatch = computed(() => (draft.value.limitCount === null) !== (draft.value.limitWindow === null))
const canSave = computed(() => target.value?.endpointStatus === 'PUBLISHED'
  && !saving.value && !stale.value && !quotaMismatch.value)

function initialDraft(grant: Grant | null): GrantDraft {
  return {
    limitCount: grant?.rateLimitCount ?? null,
    limitWindow: grant?.rateLimitWindowSeconds ?? null,
  }
}

function selectEndpoint(id: number | null) {
  const endpoint = endpoints.value.find(item => item.id === id)
  if (!endpoint && id === target.value?.endpointId)
    return
  record.value = props.grants.find(grant => grant.endpointId === id) ?? null
  draft.value = initialDraft(record.value)
  target.value = endpoint
    ? {
        endpointId: endpoint.id,
        endpointCode: endpoint.endpointCode,
        displayName: endpoint.displayName,
        endpointStatus: endpoint.status,
      }
    : null
}

function searchEndpoints(keyword: string) {
  endpointSearch.value = keyword.trim()
  endpointRequest?.abort()
  loadingEndpoints.value = true
  void debounceEndpoints()
}

async function loadEndpoints(reset: boolean) {
  if (unmounted)
    return
  const request = new AbortController()
  endpointRequest?.abort()
  endpointRequest = request
  loadingEndpoints.value = true
  endpointsFailed.value = false
  const pageNo = reset
    ? 1
    : endpointPage + 1
  try {
    const { data } = await api.endpoints({
      pageNo,
      pageSize: 20,
      status: 'PUBLISHED',
      keyword: endpointSearch.value || undefined,
    }, request.signal)
    if (!request.signal.aborted) {
      endpoints.value = reset
        ? data.pageData
        : [
            ...endpoints.value,
            ...data.pageData,
          ]
      endpointTotal.value = data.total
      endpointPage = pageNo
    }
  }
  catch {
    if (!request.signal.aborted)
      endpointsFailed.value = true
  }
  finally {
    if (!request.signal.aborted)
      loadingEndpoints.value = false
  }
}

function moreEndpoints(event: Event) {
  const box = event.target
  if (box instanceof HTMLElement && !loadingEndpoints.value && !endpointsFailed.value && endpoints.value.length < endpointTotal.value
    && box.scrollTop + box.clientHeight >= box.scrollHeight - 12) {
    void loadEndpoints(false)
  }
}

function close() {
  if (!saving.value)
    emit('close')
}

async function save() {
  if (!target.value || !canSave.value)
    return
  saving.value = true
  try {
    await api.saveGrant(props.appId, target.value.endpointId, grantPayload(draft.value, record.value?.version ?? null))
    emit('saved')
  }
  catch (error) {
    if (isConflict(error)) {
      stale.value = true
      emit('refresh')
    }
  }
  finally {
    saving.value = false
  }
}

onMounted(() => {
  if (!props.initial)
    void loadEndpoints(true)
})
onBeforeUnmount(() => {
  unmounted = true
  endpointRequest?.abort()
})
</script>
