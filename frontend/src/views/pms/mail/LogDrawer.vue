<template>
  <n-drawer :show="true" width="min(820px, 100vw)" @update:show="emit('close')">
    <n-drawer-content :title="t('发送日志详情')" closable>
      <n-spin :show="loading" class="min-h-72">
        <n-alert v-if="failed" type="error">
          {{ t('加载失败') }}
          <n-button text @click="load">
            {{ t('重试') }}
          </n-button>
        </n-alert>
        <template v-if="log">
          <n-descriptions bordered :column="2" class="log-summary">
            <n-descriptions-item :label="t('状态')">
              <StatusBadge :status="log.status" />
            </n-descriptions-item>
            <n-descriptions-item :label="t('发送方式')">
              {{ t(sendModeLabels[log.sendMode]) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('发件邮箱')">
              {{ log.accountCode }} · {{ log.senderAddress }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('接入方式')">
              {{ providerLabels[log.provider] }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('邮件模板')">
              {{ log.templateCode }} · {{ t('版本 {0}', log.templateRevision) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('收件人数')">
              {{ log.recipientCount }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('提交时间')">
              {{ logTime(log.createdAt) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('完成时间')">
              {{ logTime(log.finishedAt) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('调用方')">
              {{ log.callerKey }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('请求 ID')">
              {{ log.requestId ?? '—' }}
            </n-descriptions-item>
          </n-descriptions>
          <n-divider title-placement="left">
            {{ t('提交明细') }}
          </n-divider>
          <n-collapse :default-expanded-names="log.details.slice(0, 1).map(detail => detail.id)">
            <n-collapse-item v-for="detail in log.details" :key="detail.id" :name="detail.id" :title="t('第 {0} 次提交', detail.sequenceNo)">
              <template #header-extra>
                <StatusBadge :status="detail.status" />
              </template>
              <n-descriptions bordered :column="2" class="log-summary">
                <n-descriptions-item :label="t('收件人')" :span="2">
                  <ul v-if="detail.recipients.length" class="m-0 list-none p-0">
                    <li v-for="person in detail.recipients" :key="person.address">
                      {{ person.name ? `${person.name} · ${person.address}` : person.address }}
                    </li>
                  </ul>
                  <template v-else>
                    —
                  </template>
                </n-descriptions-item>
                <n-descriptions-item v-if="detail.recipientResults.length" :label="t('收件人结果')" :span="2">
                  <ul class="m-0 list-none p-0">
                    <li v-for="result in detail.recipientResults" :key="result.address" class="mb-4 flex flex-wrap items-center gap-x-8 gap-y-4">
                      {{ result.address }} <StatusBadge :status="result.status" />
                    </li>
                  </ul>
                </n-descriptions-item>
                <n-descriptions-item :label="t('开始时间')">
                  {{ logTime(detail.startedAt) }}
                </n-descriptions-item>
                <n-descriptions-item :label="t('完成时间')">
                  {{ logTime(detail.finishedAt) }}
                </n-descriptions-item>
                <n-descriptions-item v-if="detail.providerRequestId" :label="t('服务商请求 ID')" :span="2">
                  {{ detail.providerRequestId }}
                </n-descriptions-item>
                <n-descriptions-item v-if="detail.errorCode || detail.errorMessage" :label="t('错误信息')" :span="2">
                  {{ [detail.errorCode, detail.errorMessage].filter(Boolean).join(' · ') }}
                </n-descriptions-item>
              </n-descriptions>
            </n-collapse-item>
          </n-collapse>
          <n-empty v-if="log.details.length === 0" :description="t('暂无提交明细')" />
        </template>
      </n-spin>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { MailLogDetails, MailStatus } from './api'
import { onBeforeUnmount, ref, watch } from 'vue'
import { t } from '@/i18n'
import api from './api'
import { logTime, providerLabels, sendModeLabels, statusTag } from './presentation'

const props = defineProps<{ id: number }>()
const emit = defineEmits<{ close: [] }>()
const StatusBadge = ({ status }: { status: MailStatus }) => statusTag(status)
const loading = ref(false)
const failed = ref(false)
const log = ref<MailLogDetails>()
let request: AbortController | undefined

async function load() {
  request?.abort()
  const controller = new AbortController()
  request = controller
  log.value = undefined
  failed.value = false
  loading.value = true
  try {
    const { data } = await api.log(props.id, controller.signal)
    if (request === controller)
      log.value = data
  }
  catch {
    if (request === controller)
      failed.value = true
  }
  finally {
    if (request === controller)
      loading.value = false
  }
}

onBeforeUnmount(() => {
  request?.abort()
  request = undefined
})
watch(() => props.id, load, { immediate: true })
</script>

<style scoped>
.log-summary {
  overflow-wrap: anywhere;
}
</style>
