import type { DeliveryKind, DeliveryRecipient, DeliveryStatus } from './api'
import { NTag } from 'naive-ui'
import { h } from 'vue'
import { t } from '@/i18n'
import { formatDateTime } from '@/utils'

type Status = DeliveryStatus | DeliveryRecipient['inAppStatus'] | DeliveryRecipient['mailStatus']
const statusInfo: Record<Status, { label: string, type: 'default' | 'info' | 'success' | 'warning' | 'error' }> = {
  PENDING: { label: '待处理', type: 'default' },
  PROCESSING: { label: '处理中', type: 'info' },
  RETRY: { label: '等待重试', type: 'warning' },
  DONE: { label: '已完成', type: 'success' },
  PARTIAL: { label: '部分完成', type: 'warning' },
  SKIPPED: { label: '已跳过', type: 'default' },
  FAILED: { label: '失败', type: 'error' },
  WRITTEN: { label: '已写入', type: 'success' },
  OFF: { label: '关闭', type: 'default' },
  ACCEPTED: { label: '已受理', type: 'success' },
  UNKNOWN: { label: '结果未知', type: 'warning' },
}

const deliveryStatuses: DeliveryStatus[] = [
  'PENDING',
  'PROCESSING',
  'RETRY',
  'DONE',
  'PARTIAL',
  'SKIPPED',
  'FAILED',
]

export function deliveryStatusOptions() {
  return deliveryStatuses.map(value => ({
    value,
    label: t(statusInfo[value].label),
  }))
}

export function deliveryStatusTag(status: Status) {
  const info = statusInfo[status]
  return info
    ? h(NTag, { size: 'small', type: info.type }, () => t(info.label))
    : status
}

export function deliveryKindLabel(kind: DeliveryKind) {
  const labels = {
    NODE_APPROVED: '节点通过通知',
    NODE_REJECTED: '节点驳回通知',
    TASK_ASSIGNED_MAIL: '新待办邮件',
    TASK_RETURNED_MAIL: '退回待办邮件',
  }
  return labels[kind]
    ? t(labels[kind])
    : kind
}

export function deliveryTime(value: string | null) {
  return value
    ? formatDateTime(value)
    : '—'
}
