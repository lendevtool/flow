import type { DocumentGenerationFileType } from '../document-generation/api'
import type { DimensionSlot, DocumentDimension, DocumentOption, DocumentQuery } from './api'
import { language, t } from '@/i18n'

export const dimensionSlots = [1, 2, 3, 4, 5, 6] as const

export interface DocumentFilters {
  keyword: string
  departmentId: number | null
  productId: number | null
  dimensions: Partial<Record<DimensionSlot, string | null>>
}

export function emptyFilters(): DocumentFilters {
  return {
    keyword: '',
    departmentId: null,
    productId: null,
    dimensions: {},
  }
}

export function buildDocumentQuery(filters: DocumentFilters, pageNo: number, pageSize: number): DocumentQuery {
  const query: DocumentQuery = {
    pageNo,
    pageSize,
    keyword: filters.keyword.trim() || undefined,
    departmentId: filters.departmentId ?? undefined,
    productId: filters.productId ?? undefined,
  }
  for (const slot of dimensionSlots) {
    const value = filters.dimensions[slot]?.trim()
    if (value)
      query[`dimension${slot}Value`] = value
  }
  return query
}

export function documentSelectOptions(options: DocumentOption[], preserved?: number | string | null) {
  return options
    .filter(option => preserved === undefined || option.enabled || String(option.id) === String(preserved))
    .map(option => ({
      value: option.id,
      label: option.enabled
        ? option.name
        : t('{0}（停用）', option.name),
    }))
}

export function dimensionSelectOptions(dimension: DocumentDimension, preserved?: string | null) {
  return documentSelectOptions(dimension.options, preserved).map(option => ({
    ...option,
    value: String(option.value),
  }))
}

export function dimensionLabel(dimension: DocumentDimension) {
  return dimension.enabled
    ? dimension.name
    : t('{0}（停用）', dimension.name)
}

export function documentOptionName(options: DocumentOption[], id: number | null) {
  return id == null
    ? '—'
    : options.find(option => option.id === id)?.name ?? String(id)
}

export function dimensionValue(dimension: DocumentDimension, value: string) {
  return dimension.inputType === 'SELECT'
    ? dimension.options.find(option => String(option.id) === value)?.name ?? value
    : value
}

export function formatDocumentSize(bytes: number) {
  const formatter = new Intl.NumberFormat(language.value, { maximumFractionDigits: 1 })
  if (bytes >= 1024 * 1024)
    return `${formatter.format(bytes / (1024 * 1024))} MB`
  if (bytes >= 1024)
    return `${formatter.format(bytes / 1024)} KB`
  return `${formatter.format(bytes)} B`
}

export interface DocumentFileAccess {
  types: DocumentGenerationFileType[]
  canDownloadGenerations: boolean
}

export function documentFileAccess(roleCode: string | undefined, hasGenerationView: boolean, actions: readonly string[]): DocumentFileAccess {
  const admin = roleCode === 'SUPER_ADMIN'
  const canListTemplates = admin || actions.includes('DocumentGenerationTemplateManage')
  const canListGenerations = admin || hasGenerationView
  return {
    types: [
      ...(canListTemplates
        ? ['TEMPLATE_VERSION'] as const
        : []),
      ...(canListGenerations
        ? ['GENERATION'] as const
        : []),
    ],
    canDownloadGenerations: canListGenerations && (admin || actions.includes('DocumentGenerationReadFile')),
  }
}
