<template>
  <n-card class="mt-20" :title="t('Microsoft Authenticator 多因素认证')">
    <n-spin :show="loading">
      <n-alert v-if="failed" type="error">
        {{ t('无法读取 MFA 状态') }}
        <n-button text class="ml-8" @click="load">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <template v-if="status">
        <n-space align="center">
          <n-switch
            :value="status.globallyRequired || status.personalEnabled"
            :disabled="!status.configured || status.globallyRequired || busy"
            :aria-label="t('个人 MFA 开关')"
            @update:value="requestPreference"
          />
          <span>{{ t('个人 MFA 开关') }}</span>
          <n-tag v-if="status.globallyRequired" type="warning" size="small">
            {{ t('全局强制') }}
          </n-tag>
          <n-tag v-if="status.bound" type="success" size="small">
            {{ t('已绑定') }}
          </n-tag>
          <n-button v-if="status.bound && status.configured" secondary size="small" :disabled="busy" @click="replaceDevice">
            {{ t('更换绑定') }}
          </n-button>
        </n-space>
        <p class="mt-8 text-13 opacity-70">
          {{ t(status.configured ? '开启后，本地和域登录均需验证。' : '管理员尚未配置 MFA。') }}
        </p>
      </template>
    </n-spin>
  </n-card>

  <n-modal
    :show="Boolean(action)"
    preset="card"
    :title="t('Microsoft Authenticator 多因素认证')"
    style="width: min(440px, 92vw)"
    :closable="!busy && !recoveryCodes.length"
    :mask-closable="!busy && !recoveryCodes.length"
    :close-on-esc="!busy && !recoveryCodes.length"
    @update:show="close"
  >
    <MfaRecoveryCodes v-if="recoveryCodes.length" :codes="recoveryCodes" @done="finish" />
    <MfaChallengeForm
      v-else-if="challenge"
      :challenge="challenge"
      :loading="busy"
      @verify="confirmSetup"
      @cancel="close"
    />
    <div v-else class="flex-col gap-12">
      <p>{{ t('输入 Microsoft Authenticator 的六位验证码或一组恢复码。') }}</p>
      <n-input
        v-model:value="proof"
        :maxlength="16"
        :disabled="busy"
        :placeholder="t('输入验证码或恢复码')"
        @keydown.enter="submitProof"
      />
      <n-space>
        <n-button type="primary" :disabled="!proof.trim()" :loading="busy" @click="submitProof">
          {{ t('验证') }}
        </n-button>
        <n-button :disabled="busy" @click="close">
          {{ t('取消') }}
        </n-button>
      </n-space>
    </div>
  </n-modal>
</template>

<script setup lang="ts">
import type { MfaChallenge, MfaStatus } from '@/api/mfa'
import { onMounted, ref } from 'vue'
import api from '@/api/mfa'
import MfaChallengeForm from '@/components/mfa/MfaChallengeForm.vue'
import MfaRecoveryCodes from '@/components/mfa/MfaRecoveryCodes.vue'
import { t } from '@/i18n'
import { useAuthStore } from '@/store'

const authStore = useAuthStore()
const status = ref<MfaStatus>()
const loading = ref(false)
const failed = ref(false)
const busy = ref(false)
const action = ref<'replace' | 'enable' | 'disable'>()
const challenge = ref<MfaChallenge>()
const proof = ref('')
const recoveryCodes = ref<string[]>([])

async function load() {
  loading.value = true
  failed.value = false
  try {
    status.value = (await api.status()).data
  }
  catch {
    failed.value = true
  }
  finally {
    loading.value = false
  }
}

function openProof(next: NonNullable<typeof action.value>) {
  proof.value = ''
  action.value = next
}

async function requestPreference(enabled: boolean) {
  if (!status.value || busy.value)
    return
  if (enabled && !status.value.bound) {
    await beginSetup()
    return
  }
  openProof(enabled ? 'enable' : 'disable')
}

async function replaceDevice() {
  if (status.value?.recentRecoveryLogin) {
    await beginSetup()
    return
  }
  openProof('replace')
}

async function beginSetup(currentCode?: string) {
  busy.value = true
  try {
    challenge.value = (await api.setup(currentCode)).data
    action.value = 'replace'
    proof.value = ''
  }
  catch {
    // 统一拦截器显示业务错误。
  }
  finally {
    busy.value = false
  }
}

async function submitProof() {
  if (busy.value || !proof.value.trim())
    return
  if (action.value === 'replace') {
    await beginSetup(proof.value.trim())
    return
  }
  busy.value = true
  try {
    const enabled = action.value === 'enable'
    status.value = (await api.preference(enabled, proof.value.trim())).data
    proof.value = ''
    action.value = undefined
    if (enabled)
      await authStore.logout()
  }
  catch {
    // 统一拦截器显示业务错误。
  }
  finally {
    busy.value = false
  }
}

async function confirmSetup(code: string) {
  if (!challenge.value || busy.value)
    return
  busy.value = true
  try {
    recoveryCodes.value = (await api.confirm(challenge.value.ticket, code)).data.recoveryCodes
    challenge.value = undefined
  }
  catch {
    // 保留绑定步骤，允许重新输入验证码。
  }
  finally {
    busy.value = false
  }
}

function close() {
  if (busy.value || recoveryCodes.value.length)
    return
  action.value = undefined
  challenge.value = undefined
  proof.value = ''
}

async function finish() {
  recoveryCodes.value = []
  close()
  await authStore.logout()
}

onMounted(load)
</script>
