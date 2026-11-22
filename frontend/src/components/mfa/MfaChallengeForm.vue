<template>
  <div class="flex-col gap-12">
    <template v-if="challenge.enrollment">
      <p>{{ t('在 Microsoft Authenticator 中添加「其他账户」，扫描二维码后输入六位验证码。') }}</p>
      <img
        v-if="challenge.qrCode"
        :src="challenge.qrCode"
        :alt="t('Microsoft Authenticator 绑定二维码')"
        width="180"
        height="180"
        class="m-auto max-w-full"
      >
      <div v-if="challenge.setupKey" class="text-12 opacity-75">
        {{ t('也可手动输入绑定密钥') }}
        <code class="block select-text break-all">{{ challenge.setupKey }}</code>
      </div>
    </template>
    <p v-else>
      {{ t('输入 Microsoft Authenticator 的六位验证码或一组恢复码。') }}
    </p>
    <n-input
      v-model:value="code"
      :maxlength="challenge.enrollment ? 6 : 16"
      :input-props="{ autocomplete: 'one-time-code', inputmode: challenge.enrollment ? 'numeric' : 'text' }"
      :placeholder="t(challenge.enrollment ? '输入六位验证码' : '输入验证码或恢复码')"
      :disabled="loading"
      @keydown.enter="verify"
    />
    <n-space>
      <n-button type="primary" :disabled="!valid" :loading="loading" @click="verify">
        {{ t(challenge.enrollment ? '确认绑定' : '验证') }}
      </n-button>
      <n-button :disabled="loading" @click="emit('cancel')">
        {{ t('取消') }}
      </n-button>
    </n-space>
  </div>
</template>

<script setup lang="ts">
import type { MfaChallenge } from '@/api/mfa'
import { computed, ref } from 'vue'
import { t } from '@/i18n'

const props = defineProps<{ challenge: MfaChallenge, loading: boolean }>()
const emit = defineEmits<{ verify: [code: string], cancel: [] }>()
const code = ref('')
const valid = computed(() => props.challenge.enrollment
  ? /^\d{6}$/.test(code.value.trim())
  : /^(?:\d{6}|[\w-]{16})$/.test(code.value.trim()))

function verify() {
  if (valid.value && !props.loading)
    emit('verify', code.value.trim())
}
</script>
