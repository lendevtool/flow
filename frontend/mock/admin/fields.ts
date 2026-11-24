import type { FieldDefinition, Option } from '../../src/views/pms/field/api'
import type { FieldForm } from '../../src/views/pms/field/model'
import type { MockParams } from './params'
import { characterKeys, dataTypes, isDataType, isEnum, newField } from '../../src/views/pms/field/model.ts'
import { exampleFields, mockTime } from '../fixtures.ts'
import { MockError, workflowPage } from '../protocol.ts'
import { at, bool, collection, enabled, matchesAny, route, text, unique } from './common.ts'
import { matchesFieldPattern } from './field-pattern.js'

const typeNames = [
  '文本',
  '整数',
  '小数',
  '开关',
  '日期',
  '日期时间',
  '单选',
  '多选',
  '附件',
]
const characterNames = [
  '中文',
  '英文',
  '数字',
  '空格',
  '连接符',
  '中文标点',
  '英文标点',
]

function option(code: string, name = code, nameEn: string | null = null, enable = true): Option {
  return { code, name, nameEn, enable }
}

const characterRules = {
  CHINESE: /^\p{Script=Han}$/u,
  ENGLISH: /^[A-Z]$/i,
  DIGITS: /^\d$/,
  SPACE: /^ $/,
  SEPARATORS: /^[-_]$/,
  CHINESE_PUNCTUATION: /^[，。！？、；：“”‘’《》〈〉（）【】「」『』…—·]$/,
  ENGLISH_PUNCTUATION: /^[,.!?;:'"()[\]{}]$/,
} satisfies Record<keyof typeof characterKeys, RegExp>

function fitsNumber(config: FieldForm, value: string) {
  if (config.dataType === 'INTEGER') {
    if (!/^[+-]?\d{1,19}$/.test(value))
      return false
    const integer = BigInt(value)
    const min = BigInt(config.integerMin ?? '-9223372036854775808')
    const max = BigInt(config.integerMax ?? '9223372036854775807')
    return (!config.numberPositiveOnly || integer > 0n) && integer >= min && integer <= max
  }
  const parts = /^[+-]?(\d+)(?:\.(\d+))?$/.exec(value)
  if (!parts)
    return false
  const integerDigits = parts[1].replace(/^0+/, '').length
  const scale = parts[2]?.length ?? 0
  const precision = scale + integerDigits
  const limit = config.decimalScale ?? 18
  const maximumPrecision = config.decimalPrecision ?? 38
  const integerLimit = config.decimalPrecision !== null && config.decimalScale !== null
    ? maximumPrecision - limit
    : 20
  const numeric = Number(value)
  return scale <= limit && precision <= maximumPrecision && integerDigits <= integerLimit
    && (!config.numberPositiveOnly || numeric > 0)
    && (config.decimalMin === null || numeric >= Number(config.decimalMin))
    && (config.decimalMax === null || numeric <= Number(config.decimalMax))
}

function fitsDate(value: string, min: string | null, max: string | null, time: boolean) {
  const format = time ? /^[1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,3})?)?$/ : /^[1-9]\d{3}-\d{2}-\d{2}$/
  if (!format.test(value))
    return false
  const date = new Date(time
    ? `${value}Z`
    : value)
  return Number.isFinite(date.getTime())
    && date.toISOString().slice(0, 10) === value.slice(0, 10)
    && (!min || date.getTime() >= Date.parse(time
      ? `${min}Z`
      : min))
    && (!max || date.getTime() <= Date.parse(time
      ? `${max}Z`
      : max))
}

function fitsText(config: FieldForm, value: string) {
  const characters = Array.from(value)
  if (config.textCharLimitEnabled) {
    const allowed = (Object.keys(characterKeys) as (keyof typeof characterKeys)[])
      .filter(key => config[characterKeys[key]])
    if (characters.some(character => !allowed.some(key => characterRules[key].test(character))))
      return false
  }
  return (!config.required || !!value.trim())
    && (config.textMinLength === null || characters.length >= config.textMinLength)
    && (config.textMaxLength === null || characters.length <= config.textMaxLength)
    && (config.textFormat !== 'EMAIL' || /^[^\s@]+@[^\s@][^\s.@]*\.[^\s@]+$/.test(value))
    && (config.textFormat !== 'URL' || ['https:', 'http:'].includes(URL.parse(value)?.protocol ?? ''))
    && (!config.textPattern || matchesFieldPattern(config.textPattern, value))
}

function validAttachmentId(value: unknown): value is string {
  const maximum = '9223372036854775807'
  return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value)
    && (value.length < maximum.length || value <= maximum)
}

function fitsAttachments(value: unknown) {
  if (!Array.isArray(value))
    return false
  const documents = new Set<string>()
  for (const item of value) {
    if (!item || typeof item !== 'object' || Array.isArray(item) || Object.keys(item).length !== 2
      || !validAttachmentId(item.documentId) || !validAttachmentId(item.versionId) || documents.has(item.documentId)) {
      return false
    }
    documents.add(item.documentId)
  }
  return true
}

export function validateFieldValue(config: FieldForm, value: unknown, options: Option[]) {
  if (value === undefined || value === null || value === '' || (Array.isArray(value) && !value.length))
    return !config.required
  if (config.dataType === 'BOOLEAN')
    return typeof value === 'boolean'
  if (config.dataType === 'ENUM_MULTI') {
    return Array.isArray(value) && value.length === new Set(value).size
      && (config.enumMinItems === null || value.length >= config.enumMinItems)
      && (config.enumMaxItems === null || value.length <= config.enumMaxItems)
      && value.every(code => options.some(item => item.enable && item.code === code))
  }
  if (config.dataType === 'ATTACHMENT')
    return fitsAttachments(value)
  if (typeof value !== 'string')
    return false
  switch (config.dataType) {
    case 'INTEGER':
    case 'DECIMAL':
      return fitsNumber(config, value)
    case 'TEXT':
      return fitsText(config, value)
    case 'DATE':
      return fitsDate(value, config.dateMin, config.dateMax, false)
    case 'DATETIME':
      return fitsDate(value, config.dateTimeMin, config.dateTimeMax, true)
    case 'ENUM':
      return options.some(item => item.enable && item.code === value)
    default:
      return false
  }
}

export function createFields(params: MockParams) {
  const fields = collection<FieldDefinition>(exampleFields())
  const base = '/field-definition'
  function dictionaryOptions(id: number) {
    return params.groupItems(String(id)).map(row => option(row.code, row.name, text(row.nameEn) || null, bool(row.enable, true)))
  }
  function validate(config: FieldForm, value: unknown) {
    if (!isEnum(config.dataType) || value === null || value === undefined)
      return validateFieldValue(config, value, [])
    const id = config.paramGroupId
    return id !== null && params.groups.has(id) && bool(params.groups.get(id).enable, true)
      && validateFieldValue(config, value, dictionaryOptions(id))
  }
  function config(body: Record<string, unknown>, current?: FieldDefinition) {
    const dataType = text(body.dataType, current?.config.dataType)
    const code = text(body.code, current?.config.code)
    if (!isDataType(dataType))
      throw new MockError(400, '字段类型无效')
    unique(fields.rows().filter(row => row !== current), code, row => row.config.code)
    const next = Object.assign(newField(dataType), current?.config, body, { dataType, code })
    if (next.paramGroupId !== null)
      params.groups.get(next.paramGroupId)
    return next
  }
  const routes = [
    route('GET', base, ({ query }) => workflowPage(fields.rows().filter(({ config }) =>
      matchesAny(query.get('keyword'), config.code, config.name, config.nameEn)
      && (!query.has('dataType') || config.dataType === query.get('dataType'))
      && enabled(config.enable, query.get('enable')),
    ), query)),
    route('GET', `${base}/metadata`, () => ({
      types: dataTypes.map((code, i) => option(code, typeNames[i])),
      formats: [
        option('EMAIL', '邮箱'),
        option('URL', '网址'),
      ],
      characters: Object.keys(characterKeys).map((code, i) => option(code, characterNames[i])),
    })),
    route('GET', `${base}/dictionaries`, ({ query }) => params.groups.rows().filter(row =>
      row.id === Number(query.get('selectedId')) || matchesAny(query.get('keyword'), row.code, row.name, row.nameEn),
    ).map(row => ({
      id: row.id,
      ...option(row.code, row.name, text(row.nameEn) || null, bool(row.enable, true)),
    }))),
    route('GET', at(`${base}/dictionaries/:id/options`), ({ params }) => dictionaryOptions(Number(params[1]))),
    route('POST', base, ({ body }) => {
      const timestamp = mockTime()
      return fields.create({
        config: config(body),
        createdAt: timestamp,
        updatedAt: timestamp,
        createdBy: 1,
        updatedBy: 1,
      })
    }),
    route('PUT', at(`${base}/:id`), ({ params, body }) => {
      const row = fields.get(params[1])
      return Object.assign(row, {
        config: config(body, row),
        updatedAt: mockTime(),
        updatedBy: 1,
      })
    }),
    route('DELETE', at(`${base}/:id`), ({ params }) => {
      fields.get(params[1])
      fields.remove(params[1])
    }),
    route('POST', at(`${base}/:id/validate`), ({ params, body }) => {
      const { config } = fields.get(params[1])
      return validate(config, body.value)
        ? { valid: true, code: null, message: null }
        : { valid: false, code: 'mock.field.invalid', message: '字段值不符合当前配置' }
    }),
  ]
  return { fields, dictionaryOptions, routes }
}

export type MockFields = ReturnType<typeof createFields>
