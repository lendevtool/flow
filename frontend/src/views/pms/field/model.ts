export const dataTypes = [
  'TEXT',
  'INTEGER',
  'DECIMAL',
  'BOOLEAN',
  'DATE',
  'DATETIME',
  'ENUM',
  'ENUM_MULTI',
  'ATTACHMENT',
] as const
export type DataType = typeof dataTypes[number]
export type FieldValue = string | boolean | string[] | null

export const characterKeys = {
  CHINESE: 'textAllowChinese',
  ENGLISH: 'textAllowEnglish',
  DIGITS: 'textAllowDigits',
  SPACE: 'textAllowSpace',
  SEPARATORS: 'textAllowSeparators',
  CHINESE_PUNCTUATION: 'textAllowChinesePunctuation',
  ENGLISH_PUNCTUATION: 'textAllowEnglishPunctuation',
} as const

const emptyRules = {
  textMinLength: null as number | null,
  textMaxLength: null as number | null,
  textFormat: null as string | null,
  textPattern: null as string | null,
  textCharLimitEnabled: false,
  textAllowChinese: false,
  textAllowEnglish: false,
  textAllowDigits: false,
  textAllowSpace: false,
  textAllowSeparators: false,
  textAllowChinesePunctuation: false,
  textAllowEnglishPunctuation: false,
  numberPositiveOnly: false,
  integerMin: null as string | null,
  integerMax: null as string | null,
  decimalMin: null as string | null,
  decimalMax: null as string | null,
  decimalPrecision: null as number | null,
  decimalScale: null as number | null,
  dateMin: null as string | null,
  dateMax: null as string | null,
  dateTimeMin: null as string | null,
  dateTimeMax: null as string | null,
  paramGroupId: null as number | null,
  enumMinItems: null as number | null,
  enumMaxItems: null as number | null,
}

interface FieldConfig {
  code: string
  name: string
  nameEn: string
  dataType: DataType
  required: boolean
  defaultValue: FieldValue
  enable: boolean
  remark: string | null
}

export type FieldForm = FieldConfig & typeof emptyRules

export function newField(dataType: DataType): FieldForm {
  return {
    ...emptyRules,
    code: '',
    name: '',
    nameEn: '',
    dataType,
    required: false,
    defaultValue: null,
    enable: true,
    remark: null,
  }
}

/** 类型切换时清除旧类型的规则、字典和默认值，保留通用配置。 */
export function changeFieldType(form: FieldForm, type: DataType): FieldForm {
  return form.dataType === type
    ? form
    : {
        ...form,
        ...emptyRules,
        dataType: type,
        defaultValue: null,
      }
}

export function isDataType(value: string): value is DataType {
  return (dataTypes as readonly string[]).includes(value)
}

export function isEnum(type: DataType) {
  return type === 'ENUM' || type === 'ENUM_MULTI'
}

/** 保留输入原样，由后端判断规则；多选仅解析 JSON，不清理重复或非法元素。 */
export function validationValue(type: DataType, input: string | null): unknown {
  if (input === null)
    return null
  if (type === 'BOOLEAN')
    return input === 'true'
  if (type === 'ENUM_MULTI' || type === 'ATTACHMENT') {
    const value: unknown = JSON.parse(input)
    if (!Array.isArray(value))
      throw new TypeError('Expected a JSON array')
    return value
  }
  return input
}

export function selectedCharacters(form: FieldForm): string[] {
  return Object.entries(characterKeys).filter(([, key]) => form[key]).map(([code]) => code)
}

export function setCharacters(form: FieldForm, codes: readonly (string | number)[]) {
  for (const [code, key] of Object.entries(characterKeys))
    form[key] = codes.includes(code)
}

/** 日期选择器使用固定毫秒格式，接口返回的 ISO 日期时间可能省略秒或末尾的零。 */
export function pickerDateTime(value: string | null): string | null {
  if (!value)
    return null
  const [seconds, fraction = ''] = value.split('.')
  const complete = seconds.length === 16
    ? `${seconds}:00`
    : seconds
  return `${complete}.${fraction.padEnd(3, '0')}`
}
