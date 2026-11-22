<template>
  <n-drawer :show="true" width="min(940px, 100vw)" :close-on-esc="mailLogId === null" @update:show="!$event && emit('close')">
    <n-drawer-content :title="t('流程通知投递详情')" closable>
      <n-alert v-if="error" type="error">
        {{ errorMessage(error, t('加载失败')) }}
        <NButton text @click="reload">
          {{ t('重试') }}
        </NButton>
      </n-alert>
      <n-spin :show="loading" class="min-h-72">
        <template v-if="data">
          <n-descriptions :column="2" size="small" bordered class="delivery-summary">
            <n-descriptions-item :label="t('投递编号')">
              {{ data.delivery.id }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('流程编号')">
              <RouterLink :to="{ name: 'WorkflowTask', params: { id: data.delivery.flowInstanceId } }">
                WF-{{ data.delivery.flowInstanceId }}
              </RouterLink>
            </n-descriptions-item>
            <n-descriptions-item :label="t('场景编码')">
              {{ data.delivery.sceneCode }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('场景版本')">
              {{ data.delivery.sceneVersion ?? '—' }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('投递类型')">
              {{ deliveryKindLabel(data.delivery.kind) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('状态')">
              <StatusBadge :status="data.delivery.status" />
            </n-descriptions-item>
            <n-descriptions-item :label="t('新建时间')">
              {{ deliveryTime(data.delivery.createdAt) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('完成时间')">
              {{ deliveryTime(data.delivery.finishedAt) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('尝试次数')">
              {{ data.delivery.attemptCount }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('原因代码')">
              {{ data.delivery.lastErrorCode ?? '—' }}
            </n-descriptions-item>
          </n-descriptions>

          <n-divider title-placement="left">
            {{ t('收件人投递结果') }}
          </n-divider>
          <n-data-table
            :columns="recipientColumns"
            :data="data.recipients"
            :row-key="row => row.recipientUserId"
            :pagination="data.recipients.length > 20 ? { pageSize: 20 } : false"
            :scroll-x="850"
            size="small"
          >
            <template #empty>
              <n-empty :description="t('暂无收件人投递记录')" />
            </template>
          </n-data-table>
        </template>
      </n-spin>
    </n-drawer-content>
  </n-drawer>

  <LogDrawer v-if="mailLogId !== null && canViewMailLog" :id="mailLogId" @close="mailLogId = null" />
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { DeliveryRecipient, DeliveryStatus } from './api'
import { NButton } from 'naive-ui'
import { computed, h, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { t } from '@/i18n'
import LogDrawer from '@/views/pms/mail/LogDrawer.vue'
import api from './api'
import { deliveryKindLabel, deliveryStatusTag, deliveryTime } from './deliveryPresentation'
import { errorMessage } from './model'
import { useLatestResource } from './useLatestResource'

const props = defineProps<{ id: number, canViewMailLog: boolean }>()
const emit = defineEmits<{ close: [] }>()
const mailLogId = ref<number | null>(null)
const StatusBadge = ({ status }: { status: DeliveryStatus }) => deliveryStatusTag(status)
const { data, loading, error, reload } = useLatestResource(() => {
  const { id } = props
  return async signal => (await api.workflowDelivery(id, signal)).data
})

const recipientColumns = computed<DataTableColumns<DeliveryRecipient>>(() => [
  {
    title: t('用户 ID'),
    key: 'recipientUserId',
    width: 96,
    ellipsis: { tooltip: true },
  },
  {
    title: t('站内消息'),
    key: 'inAppStatus',
    width: 106,
    render: row => deliveryStatusTag(row.inAppStatus),
  },
  {
    title: t('站内消息原因'),
    key: 'inAppErrorCode',
    minWidth: 155,
    ellipsis: { tooltip: true },
    render: row => row.inAppErrorCode ?? '—',
  },
  {
    title: t('邮件状态'),
    key: 'mailStatus',
    width: 115,
    render: row => deliveryStatusTag(row.mailStatus),
  },
  {
    title: t('邮件原因'),
    key: 'mailErrorCode',
    minWidth: 155,
    ellipsis: { tooltip: true },
    render: row => row.mailErrorCode ?? '—',
  },
  {
    title: t('发送日志'),
    key: 'mailLogId',
    width: 135,
    ellipsis: { tooltip: true },
    render: row => props.canViewMailLog && row.mailLogId !== null
      ? h(NButton, {
          text: true,
          type: 'primary',
          onClick: () => mailLogId.value = row.mailLogId,
        }, () => `#${row.mailLogId}`)
      : row.mailLogId ?? '—',
  },
])
</script>

<style scoped>
.delivery-summary {
  overflow-wrap: anywhere;
}
</style>
