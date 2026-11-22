<template>
  <div>
    <n-alert v-if="!app.enabled" type="warning" class="mb-12">
      {{ t('应用已停用，接入方暂时无法换取令牌。') }}
    </n-alert>
    <n-alert v-if="grantsReady && !canVerify" type="warning" class="mb-12">
      {{ t('应用需要至少一个已发布且有效的接口授权，才能验证新凭据。') }}
      <n-button text @click="emit('showGrants')">
        {{ t('查看接口授权') }}
      </n-button>
    </n-alert>
    <n-alert v-if="failed" type="error" class="mb-12">
      {{ t('凭据版本加载失败') }}
      <n-button text @click="reload">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <n-alert v-if="generationFailed" type="warning" class="mb-12">
      {{ generationFailed }}
      <n-button text @click="reload">
        {{ t('刷新凭据版本') }}
      </n-button>
    </n-alert>
    <div class="mb-12 flex flex-wrap items-center justify-between gap-12">
      <n-text v-if="rotationPending" depth="3">
        {{ t('当前有未到期的候选或退役版本。请先处理该版本或等待到期。') }}
      </n-text>
      <n-button
        class="ml-auto"
        type="primary"
        :loading="generating"
        :disabled="loading || operating || failed || rotationPending || !!issued"
        @click="generate"
      >
        {{ t('生成候选密钥') }}
      </n-button>
    </div>
    <n-data-table
      :columns="columns"
      :data="versions"
      :loading="loading"
      :row-key="row => row.versionNo"
      :scroll-x="1310"
      :size="appStore.tableSize"
    />
    <n-modal
      v-if="issued"
      :show="true"
      preset="card"
      :title="t('保存 AppSecret')"
      :mask-closable="false"
      :close-on-esc="false"
      class="w-[min(620px,calc(100vw-32px))]"
      @update:show="!$event && clearIssued()"
    >
      <n-alert type="warning" class="mb-16">
        {{ t('AppSecret 仅显示这一次。请复制并保存到安全的凭据存储，然后用该候选密钥验证接入。') }}
      </n-alert>
      <n-descriptions bordered label-placement="left" :column="1">
        <n-descriptions-item :label="t('密钥版本')">
          {{ issued.versionNo }}
        </n-descriptions-item>
        <n-descriptions-item label="AppSecret">
          <span class="max-w-full inline-flex items-center gap-8">
            <code class="min-w-0 select-all break-all">{{ issued.clientSecret }}</code>
            <CopyButton :text="issued.clientSecret" :label="t('复制 AppSecret')" />
          </span>
        </n-descriptions-item>
        <n-descriptions-item :label="t('到期时间')">
          {{ businessTime(issued.expiresAt) }}
        </n-descriptions-item>
      </n-descriptions>
      <template #footer>
        <n-space justify="end">
          <n-button type="primary" @click="clearIssued">
            {{ t('已保存，关闭') }}
          </n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { SecretAction } from './model'
import type { App, Grant, IssuedSecret, SecretVersion } from '@/api/open-api/api'
import { useIntervalFn } from '@vueuse/core'
import { NTag } from 'naive-ui'
import { computed, h, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref } from 'vue'
import api from '@/api/open-api/api'
import CopyButton from '@/components/common/CopyButton.vue'
import TableActions from '@/components/common/TableActions.vue'
import { t } from '@/i18n'
import { useAppStore } from '@/store'
import { businessTime, isConflict, secretActions } from './model'

const props = defineProps<{ app: App }>()
const emit = defineEmits<{ showGrants: [] }>()
const appStore = useAppStore()
const now = ref(Date.now())
useIntervalFn(() => now.value = Date.now(), 15_000)
const versions = ref<SecretVersion[]>([])
const grants = ref<Grant[]>([])
const grantsReady = ref(false)
const loading = ref(false)
const failed = ref(false)
const generating = ref(false)
const operating = ref(false)
const issued = ref<IssuedSecret | null>(null)
const generationFailed = ref('')
let versionRequest: AbortController | undefined
let grantRequest: AbortController | undefined
let visible = true
let unmounted = false
const canVerify = computed(() => grants.value.some(grant => grant.status === 'GRANTED' && grant.endpointStatus === 'PUBLISHED'))
const rotationPending = computed(() => versions.value.some(version =>
  (version.status === 'CANDIDATE' || version.status === 'RETIRING') && Date.parse(version.expiresAt) > now.value))
const statuses = {
  CANDIDATE: { label: '候选', type: 'info' },
  ACTIVE: { label: '主用', type: 'success' },
  RETIRING: { label: '退役中', type: 'warning' },
  REVOKED: { label: '已撤销', type: 'default' },
} as const
const operations = {
  activate: {
    label: '确认切换',
    description: '确认接入方已用第 {0} 版完成验证？该版本将成为主用密钥，旧主用版本将进入退役期。',
    type: 'primary',
  },
  RETIRE: {
    label: '立即退役',
    description: '立即退役第 {0} 版后，此版本停止签发令牌。已签发的令牌可使用至原到期时间。',
    type: 'error',
  },
  ABANDON: {
    label: '废弃候选',
    description: '废弃第 {0} 版候选密钥后，此版本停止签发，其已签发的令牌将失效。',
    type: 'error',
  },
  COMPROMISED: {
    label: '应急吊销',
    description: '应急吊销第 {0} 版后，此版本停止签发，其已签发的令牌将失效。',
    type: 'error',
  },
} as const

const columns = computed<DataTableColumns<SecretVersion>>(() => [
  {
    title: t('密钥版本'),
    key: 'versionNo',
    width: 88,
  },
  {
    title: 'AppSecret',
    key: 'secret',
    width: 122,
    render: () => '••••••••••',
  },
  {
    title: t('状态'),
    key: 'status',
    width: 105,
    render: (row) => {
      const status = statuses[row.status]
      const expired = row.status !== 'REVOKED' && Date.parse(row.expiresAt) <= now.value
      return h(NTag, {
        size: 'small',
        type: expired ? 'default' : status.type,
      }, () => t(expired ? '已到期' : status.label))
    },
  },
  {
    title: t('创建时间'),
    key: 'createdAt',
    width: 157,
    render: row => businessTime(row.createdAt),
  },
  {
    title: t('启用时间'),
    key: 'activatedAt',
    width: 157,
    render: row => businessTime(row.activatedAt),
  },
  {
    title: t('到期时间'),
    key: 'expiresAt',
    width: 157,
    render: row => businessTime(row.expiresAt),
  },
  {
    title: t('撤销时间'),
    key: 'revokedAt',
    width: 157,
    render: row => businessTime(row.revokedAt),
  },
  {
    title: t('令牌吊销时间'),
    key: 'tokensRevokedAt',
    width: 157,
    render: row => businessTime(row.tokensRevokedAt),
  },
  {
    title: t('操作'),
    key: 'actions',
    width: 208,
    align: 'right',
    fixed: 'right',
    render: row => h(TableActions, {
      actions: secretActions(row.status, row.expiresAt, row.tokensRevokedAt, now.value).map(key => ({
        key,
        label: t(operations[key].label),
        type: operations[key].type,
        disabled: generating.value || operating.value || loading.value || !!issued.value,
        onClick: () => confirm(row, key),
      })),
    }),
  },
])

async function loadVersions() {
  const request = new AbortController()
  versionRequest?.abort()
  versionRequest = request
  loading.value = true
  failed.value = false
  try {
    const { data } = await api.secrets(props.app.id, request.signal)
    if (!request.signal.aborted)
      versions.value = data
  }
  catch {
    if (!request.signal.aborted)
      failed.value = true
  }
  finally {
    if (!request.signal.aborted)
      loading.value = false
  }
}

async function loadGrants() {
  const request = new AbortController()
  grantRequest?.abort()
  grantRequest = request
  try {
    const { data } = await api.grants(props.app.id, request.signal)
    if (!request.signal.aborted) {
      grants.value = data
      grantsReady.value = true
    }
  }
  catch {
    if (!request.signal.aborted)
      grantsReady.value = false
  }
}

function reload() {
  void loadVersions()
  void loadGrants()
}

function clearIssued() {
  issued.value = null
}

async function generate() {
  if (generating.value || operating.value || rotationPending.value || issued.value)
    return
  generating.value = true
  generationFailed.value = ''
  try {
    const { data } = await api.createSecret(props.app.id)
    if (visible && !unmounted)
      issued.value = data
  }
  catch (error) {
    if (!unmounted) {
      generationFailed.value = isConflict(error)
        ? t('当前有轮转操作进行中，请查看最新凭据版本。')
        : t('尚未确认密钥生成结果。请检查凭据版本，如已生成候选且未收到 AppSecret，可废弃该候选后重新生成。')
    }
  }
  finally {
    if (!unmounted) {
      generating.value = false
      void loadVersions()
    }
  }
}

function confirm(version: SecretVersion, action: SecretAction) {
  const operation = operations[action]
  window.$dialog.warning({
    title: t(operation.label),
    content: t(operation.description, version.versionNo),
    positiveText: t(operation.label),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      if (unmounted || operating.value || generating.value)
        return false
      operating.value = true
      try {
        if (action === 'activate')
          await api.activateSecret(props.app.id, version.versionNo)
        else
          await api.revokeSecret(props.app.id, version.versionNo, action)
        generationFailed.value = ''
        window.$message.success(t('操作成功'))
        return true
      }
      catch (error) {
        return isConflict(error)
      }
      finally {
        operating.value = false
        if (!unmounted)
          void loadVersions()
      }
    },
  })
}

onMounted(reload)
onActivated(() => visible = true)
onDeactivated(() => {
  visible = false
  clearIssued()
})
onBeforeUnmount(() => {
  unmounted = true
  clearIssued()
  versionRequest?.abort()
  grantRequest?.abort()
})
</script>
