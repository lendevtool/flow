<template>
  <n-drawer :show="true" width="min(540px, 100vw)" :mask-closable="!sending" :close-on-esc="!sending" @update:show="close">
    <n-drawer-content :title="t('测试发送')" :closable="!sending">
      <p class="mb-16 mt-0">
        {{ t('将使用已保存的模板「{0}」发送一封测试邮件。', template.name) }}
      </p>
      <n-form :disabled="sending || !!result" label-placement="top">
        <n-form-item :label="t('测试收件邮箱')" required>
          <n-input v-model:value="address" :maxlength="254" :input-props="{ 'aria-label': t('测试收件邮箱'), 'inputmode': 'email' }" />
        </n-form-item>
        <n-form-item v-if="needsName" :label="t('收件人姓名')" required>
          <n-input v-model:value="name" :maxlength="200" :input-props="{ 'aria-label': t('收件人姓名') }" />
        </n-form-item>
        <n-form-item v-for="key in variableNames" :key="key" :label="key" required>
          <n-input v-model:value="values[key]" :maxlength="10000" :placeholder="t('输入样例值')" :input-props="{ 'aria-label': key }" />
        </n-form-item>
      </n-form>

      <n-alert v-if="error" type="error">
        {{ error }}
      </n-alert>
      <n-alert v-if="result" :type="result.status === 'ACCEPTED' ? 'success' : result.status === 'FAILED' ? 'error' : 'info'" :title="statusLabel(result.status)">
        {{ t('发送日志 ID {0}', result.logId) }}
      </n-alert>

      <template #footer>
        <n-space justify="end">
          <n-button :disabled="sending" @click="close">
            {{ t(result ? '关闭' : '取消') }}
          </n-button>
          <n-button v-if="!finished" type="primary" :loading="sending" :disabled="!complete" @click="submit">
            {{ t(result ? '查询提交结果' : error ? '使用原请求重试' : '发送测试邮件') }}
          </n-button>
        </n-space>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { MailSendRequest, MailSendResult, MailTemplate } from './api'
import { computed, reactive, ref, watch } from 'vue'
import { t } from '@/i18n'
import api from './api'
import { statusLabel } from './presentation'
import { discoverVariables, recipientVariables } from './templateVariables'

const props = defineProps<{ template: MailTemplate }>()
const emit = defineEmits<{ close: [] }>()
const address = ref('')
const name = ref('')
const values = reactive<Record<string, string>>(Object.create(null))
const sending = ref(false)
const result = ref<MailSendResult>()
const error = ref('')
let previous: { signature: string, key: string } | undefined

const variables = computed(() => discoverVariables(props.template.subjectTemplate, props.template.bodyTemplate))
const variableNames = computed(() => variables.value.filter(key => !recipientVariables.includes(key)))
const needsName = computed(() => variables.value.includes('recipient.name'))
const complete = computed(() => /^[^\s@]+@[^\s@]+$/.test(address.value.trim())
  && (!needsName.value || !!name.value.trim()) && variableNames.value.every(key => values[key]?.trim()))
const finished = computed(() => !!result.value && !['PENDING', 'PROCESSING'].includes(result.value.status))

watch([address, name, values], () => error.value = '')

async function submit() {
  if (!complete.value || sending.value || finished.value)
    return
  const payload: Omit<MailSendRequest, 'idempotencyKey'> = {
    templateCode: props.template.code,
    recipients: [
      {
        address: address.value.trim(),
        ...(needsName.value
          ? {
              name: name.value.trim(),
            }
          : {}),
      },
    ],
    variables: Object.fromEntries(variableNames.value.map(key => [key, values[key]])),
  }
  const signature = JSON.stringify(payload)
  // 查询超时或仍在处理的原请求时，沿用幂等键，避免重新发信。
  if (signature !== previous?.signature) {
    const id = Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('')
    previous = { signature, key: `test-mail:${id}` }
  }
  error.value = ''
  sending.value = true
  try {
    const { data } = await api.send({ ...payload, idempotencyKey: previous.key })
    result.value = data
  }
  catch (failure) {
    error.value = failure && typeof failure === 'object' && 'message' in failure && typeof failure.message === 'string'
      ? failure.message
      : t('无法获取测试发送结果，请重试。')
  }
  finally {
    sending.value = false
  }
}

function close() {
  if (!sending.value)
    emit('close')
}
</script>
