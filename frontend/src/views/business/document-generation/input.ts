import type { ErrorObject } from 'ajv'
import addFormats from 'ajv-formats'
import Ajv2020 from 'ajv/dist/2020'
import { reactive } from 'vue'
import { t } from '@/i18n'

export type JsonValue = null | boolean | number | string | JsonObject | JsonValue[]
export interface JsonObject {
  [key: string]: JsonValue
}
export interface InputIssue {
  path: string
  message: string
}

export type FieldPath = readonly (string | number)[]
type InputMode = 'form' | 'json'
type Validator = (value: JsonObject) => InputIssue[]

const ajv = new Ajv2020({ allErrors: true, strict: false, ownProperties: true })
addFormats(ajv, ['date'])

export function isJsonObject(value: unknown): value is JsonObject {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value))
}

export function parseJsonObject(text: string): JsonObject {
  let object: unknown
  try {
    object = JSON.parse(text)
  }
  catch {
    throw new Error(t('JSON 语法无效'))
  }
  if (!isJsonObject(object))
    throw new TypeError(t('JSON 根节点必须是对象'))

  // 先校验语法，再扫描对象属性；JSON.parse 会覆盖同名属性。
  const stack: (Set<string> | null)[] = []
  const tokens = /("(?:[^"\\]|\\[\s\S])*")(\s*:)?|([{}[\]])/g
  for (const [, quoted, colon, bracket] of text.matchAll(tokens)) {
    if (bracket === '{') {
      stack.push(new Set())
    }
    else if (bracket === '[') {
      stack.push(null)
    }
    else if (bracket === '}' || bracket === ']') {
      stack.pop()
    }
    else if (quoted && colon) {
      const key = JSON.parse(quoted)
      const keys = stack.at(-1)
      if (keys?.has(key))
        throw new Error(t('JSON 不能包含重复属性'))
      keys?.add(key)
    }
  }

  if (containsUnsafeNumber(object))
    throw new Error(t('JSON 数字超出可用范围，请使用字符串'))
  return object
}

function containsUnsafeNumber(value: JsonValue): boolean {
  if (typeof value === 'number')
    return !Number.isFinite(value) || (Number.isInteger(value) && !Number.isSafeInteger(value))
  if (value && typeof value === 'object')
    return Object.values(value).some(containsUnsafeNumber)
  return false
}

function escapePointer(key: string | number) {
  return String(key).replace(/~/g, '~0').replace(/\//g, '~1')
}

export function createValidator(schema: JsonObject): Validator {
  let validate
  try {
    validate = ajv.compile(schema)
  }
  finally {
    ajv.removeSchema(schema)
  }
  return value => validate(value)
    ? []
    : (validate.errors ?? []).map(validationIssue)
}

function validationIssue(error: ErrorObject): InputIssue {
  let path = error.instancePath
  if (error.keyword === 'required' || error.keyword === 'additionalProperties')
    path += `/${escapePointer(error.params.missingProperty ?? error.params.additionalProperty)}`
  const message = {
    required: t('必填字段'),
    additionalProperties: t('不允许额外字段'),
    type: t('值的类型不匹配'),
    enum: t('请选择允许的值'),
    format: t('日期格式无效'),
    minimum: t('值必须大于或等于 {0}', error.params.limit),
    maximum: t('值必须小于或等于 {0}', error.params.limit),
    minItems: t('至少需要 {0} 项', error.params.limit),
    maxItems: t('最多允许 {0} 项', error.params.limit),
    minLength: t('至少需要 {0} 个字符', error.params.limit),
    maxLength: t('最多允许 {0} 个字符', error.params.limit),
  }[error.keyword] ?? error.message ?? t('输入不符合模板要求')
  return { path, message }
}

export function formatJson(value: JsonValue) {
  return JSON.stringify(value, null, 2)
}

export function createInputEditor(validator: Validator = () => []) {
  const state = reactive({
    mode: 'form' as InputMode,
    text: '{}',
    object: {} as JsonObject,
    issues: [] as InputIssue[],
    dirty: false,
  })
  let baseline = state.text

  function read(): JsonObject | undefined {
    let value: JsonObject
    try {
      value = state.mode === 'json'
        ? parseJsonObject(state.text)
        : state.object
      if (state.mode === 'form' && containsUnsafeNumber(value))
        throw new Error(t('JSON 数字超出可用范围，请使用字符串'))
    }
    catch (error) {
      state.issues = [
        {
          path: '',
          message: error instanceof Error
            ? error.message
            : t('JSON 语法无效'),
        },
      ]
      return
    }
    state.issues = validator(value)
    if (!state.issues.length)
      return value
  }

  function setObject(value: JsonObject) {
    state.object = value
    state.text = formatJson(value)
    state.dirty = state.text !== baseline
    read()
  }

  function setText(value: string) {
    state.text = value
    state.dirty = state.text !== baseline
    read()
  }

  function switchTo(mode: InputMode) {
    if (mode === state.mode)
      return true
    if (mode === 'form') {
      const value = read()
      if (!value)
        return false
      state.object = value
      state.text = formatJson(value)
      state.dirty = state.text !== baseline
    }
    state.mode = mode
    return true
  }

  function replace(value: JsonObject, confirm: () => boolean) {
    if (state.dirty && !confirm())
      return false
    baseline = formatJson(value)
    setObject(value)
    return true
  }

  function useTemplate(validate: Validator, value?: JsonObject) {
    validator = validate
    state.mode = 'form'
    replace(value ?? {}, () => true)
    if (value && state.issues.length)
      state.mode = 'json'
  }

  return {
    state,
    read,
    setObject,
    setText,
    switchTo,
    replace,
    useTemplate,
  }
}

export function updateJsonField(root: JsonObject, path: FieldPath, value: JsonValue | undefined): JsonObject {
  function update(current: JsonValue, position: number): JsonValue | undefined {
    if (position === path.length)
      return value

    const key = path[position]!
    if (Array.isArray(current) && typeof key === 'number') {
      const next = [...current]
      const updated = update(next[key] ?? null, position + 1)
      next.splice(key, 1, ...(updated === undefined
        ? []
        : [updated]))
      return next
    }
    const object = isJsonObject(current)
      ? current
      : {}
    const previous = Object.hasOwn(object, key)
      ? object[key]!
      : null
    const updated = update(previous, position + 1)
    if (updated !== undefined)
      return { ...object, [key]: updated }
    const next = { ...object }
    delete next[key]
    return next
  }

  const result = update(root, 0)
  return isJsonObject(result)
    ? result
    : root
}

function canonicalJson(value: JsonValue): string {
  if (Array.isArray(value))
    return `[${value.map(canonicalJson).join(',')}]`
  if (isJsonObject(value))
    return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonicalJson(value[key]!)}`).join(',')}}`
  return JSON.stringify(value)
}

export function createRandomKey() {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 0x0F) | 0x40
  bytes[8] = (bytes[8]! & 0x3F) | 0x80
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function createSubmissionKeys(createKey = createRandomKey) {
  const pending = new Map<string, string>()
  return {
    get(version: string, value: JsonObject) {
      const fingerprint = `${version}:${canonicalJson(value)}`
      const key = pending.get(fingerprint) ?? createKey()
      pending.set(fingerprint, key)
      return key
    },
    finish(version: string, value: JsonObject) {
      pending.delete(`${version}:${canonicalJson(value)}`)
    },
  }
}
