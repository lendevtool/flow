import dayjs from 'dayjs'
import { t } from '@/i18n'

const results = {
  SUCCESS: { label: '成功', type: 'success' },
  FAILURE: { label: '失败', type: 'error' },
  PARTIAL: { label: '部分完成', type: 'warning' },
  NO_CHANGE: { label: '无变化', type: 'default' },
  UNKNOWN: { label: '结果未确认', type: 'warning' },
}

export function resultInfo(value) {
  const result = Object.hasOwn(results, value)
    ? results[value]
    : null
  return result
    ? {
        ...result,
        label: t(result.label),
      }
    : { label: value || t('未知'), type: 'default' }
}

export function queueInfo(value) {
  return {
    PREPARED: {
      label: t('等待结果确认'),
      type: 'warning',
    },
    PENDING: {
      label: t('等待处理'),
      type: 'info',
    },
    PROCESSING: {
      label: t('处理中'),
      type: 'info',
    },
    RETRY: {
      label: t('等待重试'),
      type: 'warning',
    },
    DONE: {
      label: t('已完成'),
      type: 'success',
    },
    DEAD: {
      label: t('需人工处理'),
      type: 'error',
    },
  }[value] || { label: value || t('未知状态'), type: 'default' }
}

export function actorLabel(row) {
  if (row.actorType === 'ANONYMOUS') {
    return row.claimedAccount
      ? t('未认证访问者（声称账号：{0}）', row.claimedAccount)
      : t('未认证访问者')
  }
  const name = row.actorName || row.actorAccount || row.actorId || t('系统')
  return row.actorAccount && row.actorAccount !== name
    ? `${name}（${row.actorAccount}）`
    : name
}

export function cellText(cell) {
  if (!cell || cell.present === false)
    return t('不存在')
  if (cell.redacted)
    return t('内容不展示')
  if (cell.value === null || cell.value === undefined)
    return t('未设置')
  if (cell.value === '')
    return t('（空字符串）')
  if (cell.display !== undefined && cell.display !== null && cell.display !== '')
    return String(cell.display)
  if (typeof cell.value === 'boolean') {
    return cell.value
      ? t('是')
      : t('否')
  }
  return typeof cell.value === 'object'
    ? JSON.stringify(cell.value)
    : String(cell.value)
}

export function timeText(value) {
  return value
    ? dayjs(value).format('YYYY-MM-DD HH:mm:ss')
    : '—'
}

export function queryParams(filters, pageNo = 1, pageSize = 20) {
  const params = { pageNo, pageSize }
  for (const key of [
    'actorKeyword',
    'actorId',
    'moduleCode',
    'operationCode',
    'result',
    'objectType',
    'objectId',
    'objectCode',
    'parentType',
    'parentId',
    'requestId',
    'operationId',
  ]) {
    const value = typeof filters[key] === 'string'
      ? filters[key].trim()
      : filters[key]
    if (value !== undefined && value !== null && value !== '')
      params[key] = value
  }
  if (Array.isArray(filters.range) && filters.range.length === 2 && filters.range.every(Number.isFinite)) {
    params.startTime = new Date(filters.range[0]).toISOString()
    params.endTime = new Date(filters.range[1]).toISOString()
  }
  return params
}

export function auditObjectFilters(query) {
  const { moduleCode, objectType, objectId } = query
  if (typeof moduleCode !== 'string' || !moduleCode.trim()
    || typeof objectType !== 'string' || !objectType.trim()
    || typeof objectId !== 'string' || !objectId.trim() || objectId.length > 128) {
    return null
  }
  return {
    moduleCode: moduleCode.trim(),
    objectType: objectType.trim(),
    objectId: objectId.trim(),
  }
}

export function objectTypeLabel(value) {
  switch (value) {
    case 'NOTIFICATION_GROUP': return t('通知群组')
    case 'NOTIFICATION_SCENE': return t('通知场景')
    default: return value
  }
}

const notificationOperations = {
  'system.notification.group.create': '新建通知群组',
  'system.notification.group.update': '修改通知群组',
  'system.notification.group.status.update': '调整通知群组状态',
  'system.notification.scene.groups.replace': '修改通知场景绑定',
  'system.notification.workflow.scene.create': '新建流程通知场景',
  'system.notification.workflow.scene.update': '修改流程通知场景',
}

export function operationSummary({ operationCode, summary }) {
  const label = Object.hasOwn(notificationOperations, operationCode) && notificationOperations[operationCode]
  if (!label || typeof summary !== 'string')
    return summary
  const translated = t(label)
  if (translated === label)
    return summary
  if (summary === label)
    return translated
  const prefix = `${label}：`
  return summary.startsWith(prefix)
    ? `${translated} · ${summary.slice(prefix.length)}`
    : summary
}
