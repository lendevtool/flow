import type { ListFilters, MailStatus, Provider, SendMode } from './api'
import { NTag } from 'naive-ui'
import { h } from 'vue'
import { t } from '@/i18n'
import { formatDateTime } from '@/utils'

export const providerLabels: Record<Provider, string> = {
  IMAP_SMTP: 'IMAP + SMTP',
  GRAPH: 'Microsoft Graph',
}

export const sendModeLabels: Record<SendMode, string> = {
  INDIVIDUAL: '逐个发送',
  TOGETHER: '一起发送',
}

const statusInfo: Record<MailStatus, { label: string, type: 'default' | 'info' | 'success' | 'warning' | 'error' }> = {
  PENDING: { label: '待提交', type: 'default' },
  PROCESSING: { label: '提交中', type: 'info' },
  ACCEPTED: { label: '已受理', type: 'success' },
  PARTIAL: { label: '部分受理', type: 'warning' },
  FAILED: { label: '失败', type: 'error' },
  UNKNOWN: { label: '结果未知', type: 'warning' },
}

export function statusLabel(status: MailStatus) {
  return t(statusInfo[status].label)
}

export function statusTag(status: MailStatus) {
  return h(NTag, { size: 'small', type: statusInfo[status].type }, () => statusLabel(status))
}

export function enableTag(enabled: boolean) {
  return h(NTag, {
    size: 'small',
    type: enabled ? 'success' : 'default',
  }, () => t(enabled ? '启用' : '停用'))
}

export function statusOptions() {
  return Object.entries(statusInfo).map(([value, { label }]) => ({
    label: t(label),
    value,
  }))
}

export function enableOptions() {
  return [
    {
      label: t('启用'),
      value: 1,
    },
    {
      label: t('停用'),
      value: 0,
    },
  ]
}

export function listFilters(): ListFilters {
  return { keyword: null, enable: null }
}

export function logTime(value: string | null) {
  return value
    ? formatDateTime(value)
    : '—'
}

export function isMailCode(value: string) {
  return /^[A-Z0-9][\w.-]{0,63}$/i.test(value)
}
