import type { HandlingMode, WorkflowDefinition, WorkflowDraft, WorkflowNode, WorkflowStatus } from './api'
import type { DataType } from '@/views/pms/field/model'
import { t } from '@/i18n'
import { formatDateTime } from '@/utils'

const fieldTypes: Record<DataType, string> = {
  TEXT: '文本',
  INTEGER: '整数',
  DECIMAL: '小数',
  BOOLEAN: '布尔',
  DATE: '日期',
  DATETIME: '日期时间',
  ENUM: '枚举单选',
  ENUM_MULTI: '枚举多选',
  ATTACHMENT: '附件',
}

export function fieldTypeLabel(type?: DataType) {
  return type
    ? t(fieldTypes[type])
    : '—'
}

export function statusLabel(status: WorkflowStatus) {
  return t({ DRAFT: '草稿', PUBLISHED: '已发布', RETIRED: '已停用' }[status])
}

export const handlingModeLabels: Record<HandlingMode, string> = {
  PRIMARY: '默认单人',
  BROADCAST: '广播',
  ALL: '会签',
  MULTI_ALL: '多角色会签',
}

export function handlingRoles(node: WorkflowNode) {
  if (node.mode === 'MULTI_ALL')
    return node.roleCodes ?? []
  return node.roleCode
    ? [node.roleCode]
    : []
}

export function isCountersign(mode: WorkflowNode['mode']) {
  return mode === 'ALL' || mode === 'MULTI_ALL'
}

export function fromWorkflowDefinition(definition: WorkflowDefinition, clone: boolean): WorkflowDraft {
  return {
    code: definition.code,
    name: definition.name,
    ...(clone
      ? { sourceDefinitionId: definition.id }
      : { version: definition.version }),
    fields: definition.fields.map(field => field.code),
    nodes: definition.nodes.map(node => ({ ...node, requiredFields: node.requiredFields ?? [] })),
  }
}

export function dateTime(value?: string | null) {
  return value
    ? formatDateTime(value)
    : '—'
}

const publicationFormat = new Intl.DateTimeFormat('sv-SE', {
  dateStyle: 'short',
  timeStyle: 'medium',
  timeZone: 'Asia/Shanghai',
})

export function publicationTime(value?: string | null) {
  return value
    ? publicationFormat.format(new Date(`${value}Z`))
    : '—'
}
