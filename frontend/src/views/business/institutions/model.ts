import type { ChangeStatus, ChangeWrite, Institution, InstitutionOption, InstitutionOptions, InstitutionWrite } from './api'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'

export const institutionTexts = [
  {
    key: 'code',
    code: 'institution_code',
    label: '机构编码',
    maxlength: 64,
  },
  {
    key: 'nameZh',
    code: 'institution_name_zh',
    label: '中文全称',
    maxlength: 200,
  },
  {
    key: 'nameEn',
    code: 'institution_name_en',
    label: '英文全称',
    maxlength: 200,
  },
  {
    key: 'registrationId',
    code: 'institution_registration_id',
    label: '注册识别号',
    maxlength: 100,
  },
  {
    key: 'shortNameZh',
    code: 'institution_short_name_zh',
    label: '中文简称',
    maxlength: 100,
  },
  {
    key: 'shortNameEn',
    code: 'institution_short_name_en',
    label: '英文简称',
    maxlength: 100,
  },
  {
    key: 'officeAddress',
    code: 'institution_office_address',
    label: '办公地址',
    maxlength: 500,
  },
  {
    key: 'website',
    code: 'institution_website',
    label: '官网',
    maxlength: 500,
  },
  {
    key: 'remark',
    code: 'institution_remark',
    label: '备注',
    maxlength: 1000,
  },
] as const

export const institutionEnums = [
  {
    key: 'typeItemId',
    code: 'institution_type',
    label: '机构类型',
    options: 'types',
  },
  {
    key: 'countryRegionItemId',
    code: 'institution_country_region',
    label: '注册国家或地区',
    options: 'countries',
  },
] as const

export const changeReasonField = { code: 'institution_change_reason', label: '变更原因', maxlength: 500 } as const

type InstitutionTextKey = (typeof institutionTexts)[number]['key']
type InstitutionEnumKey = (typeof institutionEnums)[number]['key']
export type InstitutionDraft = Record<InstitutionTextKey, string> & Record<InstitutionEnumKey, number | null>

export const approvalFields = [
  {
    code: 'institution_name_zh',
    key: 'nameZh',
    label: '中文全称',
    required: true,
    maxlength: 200,
  },
  {
    code: 'institution_name_en',
    key: 'nameEn',
    label: '英文全称',
    required: false,
    maxlength: 200,
  },
  {
    code: 'institution_type',
    key: 'typeItemId',
    label: '机构类型',
    required: true,
    options: 'types',
  },
  {
    code: 'institution_country_region',
    key: 'countryRegionItemId',
    label: '注册国家或地区',
    required: true,
    options: 'countries',
  },
  {
    code: 'institution_registration_id',
    key: 'registrationId',
    label: '注册识别号',
    required: false,
    maxlength: 100,
  },
] as const

export type ApprovalField = (typeof approvalFields)[number]
export type ApprovalCode = ApprovalField['code']
export type ChangeDraft = Partial<Record<ApprovalCode, string | null>>

export const changeStatus = {
  PENDING: { label: '审批中', type: 'warning' },
  APPLIED: { label: '已生效', type: 'success' },
  CONFLICT: { label: '生效冲突', type: 'error' },
  CANCELLED: { label: '已取消', type: 'default' },
} as const satisfies Record<ChangeStatus, { label: string, type: string }>

export function institutionDraft(initial: Institution | null): InstitutionDraft {
  return {
    code: initial?.code ?? '',
    nameZh: initial?.nameZh ?? '',
    nameEn: initial?.nameEn ?? '',
    typeItemId: initial?.typeItemId ?? null,
    countryRegionItemId: initial?.countryRegionItemId ?? null,
    registrationId: initial?.registrationId ?? '',
    shortNameZh: initial?.shortNameZh ?? '',
    shortNameEn: initial?.shortNameEn ?? '',
    officeAddress: initial?.officeAddress ?? '',
    website: initial?.website ?? '',
    remark: initial?.remark ?? '',
  }
}

export function institutionWrite(draft: InstitutionDraft, version?: number): InstitutionWrite {
  return {
    code: draft.code.trim(),
    nameZh: draft.nameZh.trim(),
    nameEn: draft.nameEn.trim() || null,
    typeItemId: draft.typeItemId,
    countryRegionItemId: draft.countryRegionItemId,
    registrationId: draft.registrationId.trim() || null,
    shortNameZh: draft.shortNameZh.trim() || null,
    shortNameEn: draft.shortNameEn.trim() || null,
    officeAddress: draft.officeAddress.trim() || null,
    website: draft.website.trim() || null,
    remark: draft.remark.trim() || null,
    ...(version === undefined
      ? {}
      : { version }),
  }
}

export function changeWrite(version: number, reason: string, codes: ApprovalCode[], draft: ChangeDraft): ChangeWrite {
  return {
    version,
    reason: reason.trim(),
    fields: Object.fromEntries(codes.map(code => [code, draft[code]?.trim() || null])),
  }
}

export function fieldLabel(options: InstitutionOptions, field: { code: string, label: string }): string {
  const definition = options.fieldDefinitions?.[field.code]
  return displayName(definition?.name, definition?.nameEn) || t(field.label)
}

export function fieldLength(options: InstitutionOptions, field: { code: string, maxlength: number }): number {
  return Math.min(field.maxlength, options.fieldDefinitions?.[field.code]?.maxLength ?? field.maxlength)
}

function optionLabel(option: InstitutionOption): string {
  const name = displayName(option.name, option.nameEn)
  return option.enabled
    ? name
    : t('{0}（停用）', name)
}

/** 筛选可以选历史值，编辑时仅保留当前档案已经使用的停用项。 */
export function institutionSelectOptions(options: InstitutionOption[], preserved?: number | null) {
  return options
    .filter(option => preserved === undefined || option.enabled || option.id === preserved)
    .map(option => ({
      value: option.id,
      label: optionLabel(option),
    }))
}

export function approvalSelectOptions(options: InstitutionOption[], frozenCodes?: string[]) {
  return options.filter(option => option.enabled && (frozenCodes === undefined || frozenCodes.includes(option.code)))
    .map(option => ({
      value: option.code,
      label: optionLabel(option),
    }))
}

export function availableApprovalFields(options: InstitutionOptions) {
  if (options.approvalOptions === null)
    return []
  const fieldCodes = options.approvalOptions?.fields
  return approvalFields.filter(field => fieldCodes === undefined || fieldCodes.includes(field.code))
}

export function institutionOptionName(options: InstitutionOption[], id: number): string {
  const match = options.find(option => option.id === id)
  return match
    ? optionLabel(match)
    : String(id)
}

export function approvalValue(options: InstitutionOptions, record: Institution, field: ApprovalField): string | null {
  if ('options' in field)
    return options[field.options].find(option => option.id === record[field.key])?.code ?? null
  return record[field.key]
}

export function approvalValueLabel(options: InstitutionOptions, field: ApprovalField, value: string | null): string {
  if (value === null)
    return '—'
  const match = 'options' in field
    ? options[field.options].find(option => option.code === value)
    : undefined
  return match
    ? optionLabel(match)
    : value
}

export function routeId(value: unknown): number | null {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value))
    return null
  const id = Number(value)
  return Number.isSafeInteger(id)
    ? id
    : null
}
