<template>
  <section class="mt-12 border-t border-gray-500/20 border-solid pt-12">
    <h3 class="mb-8 font-medium">
      {{ t('MFA 密钥管理') }}
    </h3>
    <n-spin :show="loading">
      <n-alert v-if="failed" type="error">
        {{ t('无法读取 MFA 密钥版本') }}
        <n-button text class="ml-8" @click="load">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <template v-if="snapshot">
        <n-alert v-if="!snapshot.configured" type="warning" class="mb-12">
          {{ t('部署环境配置 MFA_KEY_STORAGE_ENCRYPTION_KEY 后，可在此保存和发布密钥。') }}
        </n-alert>
        <p class="mb-8 opacity-75">
          {{ t('保存后加密入库，点击刷新生效后发布。已保存版本只展示版本标识和状态。') }}
        </p>
        <div class="mb-12 flex flex-wrap items-end gap-8">
          <label class="w-160">
            <span class="mb-4 block">{{ t('密钥版本标识') }}</span>
            <n-input
              v-model:value="keyId"
              :maxlength="32"
              :disabled="disabled"
              :input-props="{ 'aria-label': t('密钥版本标识') }"
              placeholder="v2"
            />
          </label>
          <label class="w-280">
            <span class="mb-4 block">{{ t('MFA 加密密钥') }}</span>
            <n-input
              v-model:value="keyValue"
              type="password"
              :disabled="disabled"
              :input-props="{ 'aria-label': t('MFA 加密密钥'), 'autocomplete': 'off', 'spellcheck': false }"
              :placeholder="t('32 字节密钥的 Base64 编码')"
            />
          </label>
          <n-button :disabled="disabled" :loading="generating" @click="generate">
            {{ t('随机生成') }}
          </n-button>
          <n-button :disabled="disabled || !validInput" :loading="saving" @click="stage">
            {{ t('保存密钥') }}
          </n-button>
        </div>

        <n-table v-if="snapshot.versions.length" size="small" class="mb-12">
          <thead>
            <tr>
              <th scope="col">
                {{ t('密钥版本标识') }}
              </th>
              <th scope="col">
                {{ t('状态') }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="version in snapshot.versions" :key="version.id">
              <td>{{ version.id }}</td>
              <td>{{ t(stateLabels[version.status]) }}</td>
            </tr>
          </tbody>
        </n-table>

        <div class="flex flex-wrap items-end gap-8">
          <div class="w-240">
            <div class="mb-4">
              {{ t('预置目标版本') }}
            </div>
            <n-select
              v-model:value="target"
              clearable
              :options="targets"
              :disabled="disabled"
              :aria-label="t('预置目标版本')"
            />
          </div>
          <n-checkbox v-model:checked="activate" class="self-center" :disabled="disabled || !target">
            {{ t('发布后立即启用目标版本') }}
          </n-checkbox>
          <n-popconfirm
            :positive-text="t('确定')"
            :negative-text="t('取消')"
            @positive-click="publish"
          >
            <template #trigger>
              <n-button type="primary" :disabled="disabled" :loading="publishing">
                {{ t('刷新生效') }}
              </n-button>
            </template>
            {{ t('确认所有实例均已配置同一保护密钥。启用目标版本后会分批迁移现有绑定。') }}
          </n-popconfirm>
          <n-button :disabled="generating || saving || publishing || loading" @click="load">
            {{ t('重新读取版本') }}
          </n-button>
        </div>
        <p v-if="snapshot.lastPublishedAt" class="mt-8 opacity-75">
          {{ t('上次发布') }} {{ formatDateTime(snapshot.lastPublishedAt) }}
        </p>
        <p v-if="feedback" role="status" class="mt-8">
          {{ t(feedback) }}
        </p>
      </template>
    </n-spin>
  </section>
</template>

<script setup lang="ts">
import type { MfaKeyManagementSnapshot, MfaKeyVersionState } from './api'
import { computed, onMounted, ref } from 'vue'
import { t } from '@/i18n'
import { formatDateTime } from '@/utils'
import api from './api'

const emit = defineEmits<{ published: [] }>()
const stateLabels: Record<MfaKeyVersionState, string> = {
  DRAFT: '待发布',
  PUBLISHED: '已发布',
}
const snapshot = ref<MfaKeyManagementSnapshot>()
const loading = ref(false)
const failed = ref(false)
const generating = ref(false)
const saving = ref(false)
const publishing = ref(false)
const keyId = ref('')
const keyValue = ref('')
const target = ref<string | null>(null)
const activate = ref(true)
const feedback = ref('')
const disabled = computed(() => !snapshot.value?.configured || loading.value || generating.value || saving.value || publishing.value)
const validInput = computed(() => /^[\w-]{1,32}$/.test(keyId.value) && keyValue.value.length > 0)
const targets = computed(() => snapshot.value?.versions
  .filter(version => version.eligible)
  .map(version => ({ label: version.id, value: version.id })) ?? [])

function apply(value: MfaKeyManagementSnapshot) {
  snapshot.value = value
  target.value = targets.value.some(option => option.value === value.targetKeyId)
    ? value.targetKeyId
    : null
}

async function load() {
  loading.value = true
  feedback.value = ''
  try {
    apply((await api.getMfaKeys()).data)
    failed.value = false
  }
  catch {
    failed.value = true
  }
  finally {
    loading.value = false
  }
}

async function generate() {
  if (disabled.value)
    return
  generating.value = true
  feedback.value = ''
  try {
    keyValue.value = (await api.generateMfaKey()).data.key
    feedback.value = '已生成随机密钥，保存后才会入库。'
  }
  catch {
    // 接口错误由请求层展示。
  }
  finally {
    generating.value = false
  }
}

async function stage() {
  if (disabled.value || !validInput.value)
    return
  const id = keyId.value
  saving.value = true
  feedback.value = ''
  try {
    apply((await api.stageMfaKey(id, keyValue.value.trim())).data)
    target.value = id
    keyId.value = ''
    feedback.value = '密钥已保存，等待刷新发布。'
  }
  catch {
    // 接口错误由请求层展示。
  }
  finally {
    keyValue.value = ''
    saving.value = false
  }
}

async function publish() {
  if (disabled.value || !snapshot.value)
    return false
  publishing.value = true
  feedback.value = ''
  try {
    apply((await api.refreshMfaKeys(snapshot.value.revision, target.value, !!target.value && activate.value)).data)
    feedback.value = 'MFA 密钥已刷新生效。'
    emit('published')
  }
  catch {
    return false
  }
  finally {
    publishing.value = false
  }
}

onMounted(load)
</script>
