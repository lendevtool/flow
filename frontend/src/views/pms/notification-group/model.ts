import type { TreeOption } from 'naive-ui'
import type { DepartmentOption, GroupWrite, SceneType, Sources, SourceStatus, WorkflowSceneWrite } from './api'
import { t } from '@/i18n'

export const PAGE_SIZE = 20
export const MAX_USERS = 200
export const MAX_DEPARTMENTS = 100
export const MAX_GROUPS_PER_SCENE = 20
export const SCENE_CODE = /^[a-z][a-z0-9_.]{0,63}$/

export function sceneTypeLabel(sceneType: SceneType) {
  return t({ BUSINESS: '业务场景', WORKFLOW_SYSTEM: '系统办理提醒', WORKFLOW_EXTRA: '流程附加场景' }[sceneType] ?? '业务场景')
}

export function workflowSceneWrite(form: WorkflowSceneWrite, expectedVersion?: number): WorkflowSceneWrite & { expectedVersion?: number } {
  return {
    sceneCode: form.sceneCode.trim(),
    nameCn: form.nameCn.trim(),
    nameEn: form.nameEn?.trim() || null,
    titleTemplate: form.titleTemplate?.trim() || null,
    summaryTemplate: form.summaryTemplate?.trim() || null,
    enabled: form.enabled,
    mailTemplateId: form.mailTemplateId,
    ...(expectedVersion === undefined
      ? {}
      : { expectedVersion }),
  }
}

export function selectPage<T extends { id: number }>(
  previous: ReadonlyMap<number, T>,
  rows: readonly T[],
  checked: readonly (string | number)[],
  maximum: number,
): ReadonlyMap<number, T> {
  const next = new Map(previous)
  const checkedIds = new Set(checked)
  for (const row of rows) {
    if (checkedIds.has(row.id))
      next.set(row.id, row)
    else
      next.delete(row.id)
  }
  return next.size <= maximum
    ? next
    : previous
}

export function sourceWrite(sources: Sources): Pick<GroupWrite, 'userIds' | 'departments'> {
  return {
    userIds: sources.users.map(user => user.userId),
    departments: sources.departments.map(({ departmentId, includeDescendants }) => ({ departmentId, includeDescendants })),
  }
}

export function countTrimmedCharacters(value: string) {
  return [
    ...value.trim(),
  ].length
}

export function groupWrite(name: string, description: string, sources: Sources): GroupWrite {
  return {
    name: name.trim(),
    description: description.trim() || null,
    ...sourceWrite(sources),
  }
}

export function departmentTree(rows: readonly DepartmentOption[], excluded: ReadonlySet<number>) {
  const roots: TreeOption[] = []
  const options = new Map<number, TreeOption>()
  const paths = new Map<number, string>()
  const byId = new Map(rows.map(row => [row.departmentId, row]))
  const ordered = [...rows].sort((a, b) => a.sortOrder - b.sortOrder || a.departmentId - b.departmentId)
  for (const { departmentId, name } of ordered) {
    options.set(departmentId, {
      key: departmentId,
      label: name,
      checkboxDisabled: excluded.has(departmentId),
    })
  }

  function path(id: number): string {
    const cached = paths.get(id)
    if (cached !== undefined)
      return cached
    const row = byId.get(id)
    if (!row)
      return ''
    const parentPath = row.parentId == null
      ? ''
      : path(row.parentId)
    const value = parentPath
      ? `${parentPath} / ${row.name}`
      : row.name
    paths.set(id, value)
    return value
  }

  for (const { departmentId, parentId } of ordered) {
    const item = options.get(departmentId)!
    const parent = parentId == null
      ? undefined
      : options.get(parentId)
    if (parent)
      (parent.children ??= []).push(item)
    else
      roots.push(item)
    path(departmentId)
  }
  return { roots, paths }
}

export function sourceStatusLabel(status: SourceStatus) {
  switch (status) {
    case 'ACTIVE': return t('有效')
    case 'DISABLED': return t('来源已停用')
    case 'MISSING': return t('已删除')
  }
}

interface RequestError {
  message?: unknown
  error?: { data?: { reason?: unknown, usageCount?: unknown } }
}

export function errorMessage(error: unknown, fallback: string): string {
  const message = (error as RequestError | null)?.message
  return typeof message === 'string' && message.trim()
    ? message
    : fallback
}

export function errorReason(error: unknown) {
  return (error as RequestError | null)?.error?.data?.reason
}

export function inUseText(error: unknown) {
  const count = (error as RequestError | null)?.error?.data?.usageCount
  return typeof count === 'number'
    ? t('此群组仍被 {0} 个场景引用，请先解除绑定。', count)
    : t('此群组仍被场景引用，请先解除绑定。')
}

export function auditLocation(objectType: 'NOTIFICATION_GROUP' | 'NOTIFICATION_SCENE', id: number | string) {
  return {
    name: 'AuditView',
    query: {
      moduleCode: 'NOTIFICATION_GROUP',
      objectType,
      objectId: String(id),
    },
  }
}

export function confirmReplaceDraft(reload: () => void) {
  window.$dialog.warning({
    title: t('重新加载已保存配置'),
    content: t('重新加载成功后，当前草稿将被已保存的配置替换。'),
    positiveText: t('重新加载'),
    negativeText: t('取消'),
    onPositiveClick: reload,
  })
}
