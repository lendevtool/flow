<template>
  <n-drawer :show="true" width="min(720px, 100vw)" :mask-closable="!saving" :close-on-esc="!saving" @update:show="close">
    <n-drawer-content :title="t(initial ? '编辑邮箱' : '新增邮箱')" :closable="!saving">
      <n-form :disabled="saving" label-placement="top" @submit.prevent="requestSave">
        <div class="mail-form-grid">
          <n-form-item :label="t('编码')" required>
            <n-input v-model:value="form.code" :maxlength="64" />
          </n-form-item>
          <n-form-item :label="t('名称')" required>
            <n-input v-model:value="form.name" :maxlength="100" />
          </n-form-item>
          <n-form-item :label="t('邮箱地址')" required>
            <n-input v-model:value="form.mailboxAddress" :maxlength="254" />
          </n-form-item>
          <n-form-item :label="t('接入方式')" required>
            <n-select v-model:value="form.provider" :options="providers" :disabled="!!initial || saving" />
          </n-form-item>
        </div>
        <div class="mb-12 flex items-center gap-12">
          <n-switch v-model:value="form.enable" :aria-label="t('启用')" />
          <span>{{ t(form.enable ? '启用' : '停用') }}</span>
        </div>

        <n-alert v-if="keepCredentials" type="info" class="mb-8">
          {{ form.provider === 'GRAPH'
            ? t('更改租户 ID 或应用客户端 ID 时，请重新填写客户端密钥。保留原设置时，密钥可以留空。')
            : t('更改服务器、端口、安全连接或用户名时，请重新填写对应协议的密码。保留原设置时，密码可以留空。') }}
        </n-alert>
        <template v-if="form.provider === 'IMAP_SMTP'">
          <template v-for="protocol in protocols" :key="protocol">
            <n-divider title-placement="left">
              {{ protocol.toUpperCase() }}
            </n-divider>
            <div class="mail-form-grid">
              <n-form-item :label="t('服务器')" required>
                <n-input v-model:value="form[protocol].host" :maxlength="253" />
              </n-form-item>
              <n-form-item :label="t('端口')" required>
                <n-input-number v-model:value="form[protocol].port" class="w-full" :min="1" :max="65535" :precision="0" />
              </n-form-item>
              <n-form-item :label="t('安全连接')" required>
                <n-select v-model:value="form[protocol].tls" :options="tlsOptions" />
              </n-form-item>
              <n-form-item :label="t('用户名')" required>
                <n-input v-model:value="form[protocol].username" :maxlength="254" />
              </n-form-item>
              <n-form-item :label="t('密码')" :required="!keepCredentials">
                <n-input v-model:value="form[protocol].password" type="password" :maxlength="2048" :input-props="{ autocomplete: 'new-password' }" />
              </n-form-item>
            </div>
          </template>
        </template>
        <template v-else>
          <n-divider title-placement="left">
            Microsoft Graph
          </n-divider>
          <div class="mail-form-grid">
            <n-form-item :label="t('租户 ID')" required>
              <n-input v-model:value="form.graph.tenantId" :maxlength="253" />
            </n-form-item>
            <n-form-item :label="t('应用客户端 ID')" required>
              <n-input v-model:value="form.graph.clientId" :maxlength="36" />
            </n-form-item>
            <n-form-item :label="t('Graph 邮箱标识')">
              <n-input v-model:value="form.graph.mailboxId" :maxlength="254" :placeholder="t('Entra 用户 ID 或 UPN，默认使用邮箱地址')" />
            </n-form-item>
            <n-form-item :label="t('客户端密钥')" :required="!keepCredentials">
              <n-input v-model:value="form.clientSecret" type="password" :maxlength="2048" :input-props="{ autocomplete: 'new-password' }" />
            </n-form-item>
          </div>
        </template>
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
import type { AccountWrite, MailAccount, MailEndpoint, Provider } from './api'
import { computed, reactive, ref } from 'vue'
import { t } from '@/i18n'
import api from './api'
import { isMailCode, providerLabels } from './presentation'

const props = defineProps<{ initial: MailAccount | null }>()
const emit = defineEmits<{ close: [], saved: [] }>()
const providers = Object.entries(providerLabels).map(([value, label]) => ({ value, label }))
const protocols = ['imap', 'smtp'] as const
const tlsOptions = ['SSL', 'STARTTLS'].map(value => ({ value, label: value }))

function endpoint(initial: MailEndpoint | undefined, port: number) {
  return {
    host: '',
    port: port as number | null,
    username: '',
    tls: 'SSL' as const,
    ...initial,
    password: '',
  }
}

const initial = props.initial
const form = reactive({
  code: initial?.code ?? '',
  name: initial?.name ?? '',
  enable: initial?.enable ?? true,
  provider: initial?.provider ?? 'IMAP_SMTP' as Provider,
  mailboxAddress: initial?.mailboxAddress ?? '',
  imap: endpoint(initial?.config.imap, 993),
  smtp: endpoint(initial?.config.smtp, 465),
  graph: {
    tenantId: '',
    clientId: '',
    mailboxId: '',
    ...initial?.config.graph,
  },
  clientSecret: '',
})
const saving = ref(false)
const keepCredentials = computed(() => initial?.credentialConfigured === true && form.provider === initial.provider)
const complete = computed(() => isMailCode(form.code.trim()) && !!form.name.trim() && !!form.mailboxAddress.trim()
  && (form.provider === 'GRAPH'
    ? !!form.graph.tenantId.trim() && !!form.graph.clientId.trim() && (keepCredentials.value || !!form.clientSecret.trim())
    : protocols.every((protocol) => {
        const { host, port, username, password } = form[protocol]
        return !!host.trim() && port !== null && !!username.trim() && (keepCredentials.value || !!password.trim())
      })))

function close() {
  if (!saving.value)
    emit('close')
}

function connection({ host, port, username, tls }: typeof form.imap): MailEndpoint {
  return {
    host: host.trim(),
    port: port ?? 0,
    username: username.trim(),
    tls,
  }
}

function requestSave() {
  if (saving.value || !complete.value)
    return
  if (initial?.enable && !form.enable) {
    window.$dialog.warning({
      title: t('停用邮箱'),
      content: t('停用邮箱「{0}」后，无法使用该邮箱收发邮件。', initial.name),
      positiveText: t('停用'),
      negativeText: t('取消'),
      onPositiveClick: save,
    })
    return
  }
  void save()
}

async function save() {
  if (saving.value || !complete.value)
    return
  saving.value = true
  const graph = form.provider === 'GRAPH'
  const data: AccountWrite = {
    code: form.code.trim().toUpperCase(),
    name: form.name.trim(),
    enable: form.enable,
    provider: form.provider,
    mailboxAddress: form.mailboxAddress.trim(),
    config: graph
      ? {
          graph: {
            tenantId: form.graph.tenantId.trim(),
            clientId: form.graph.clientId.trim(),
            mailboxId: form.graph.mailboxId?.trim() || undefined,
          },
        }
      : {
          imap: connection(form.imap),
          smtp: connection(form.smtp),
        },
    credentials: graph
      ? {
          clientSecret: form.clientSecret.trim()
            ? form.clientSecret
            : undefined,
        }
      : {
          imapPassword: form.imap.password.trim()
            ? form.imap.password
            : undefined,
          smtpPassword: form.smtp.password.trim()
            ? form.smtp.password
            : undefined,
        },
  }
  try {
    if (initial)
      await api.updateAccount(initial.id, data)
    else
      await api.createAccount(data)
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
.mail-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 16px;
}

@media (max-width: 600px) {
  .mail-form-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
