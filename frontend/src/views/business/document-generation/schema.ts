import type { JsonObject, JsonValue } from './input'
import { t } from '@/i18n'
import { isJsonObject } from './input'

export function resolveFieldSchema(value: JsonValue | undefined, root: JsonObject, seen = new Set<string>()): JsonObject {
  if (!isJsonObject(value))
    return {}
  const ref = value.$ref
  if (typeof ref !== 'string' || !ref.startsWith('#') || seen.has(ref))
    return value
  seen.add(ref)
  let pointer: string
  try {
    pointer = decodeURIComponent(ref.slice(1))
  }
  catch {
    return value
  }
  const target = pointer === ''
    ? root
    : pointer.startsWith('/')
      ? pointer.slice(1).split('/').reduce<JsonValue | undefined>((node, key) =>
          schemaProperty(node, key.replace(/~1/g, '/').replace(/~0/g, '~')), root)
      : undefined
  return {
    ...resolveFieldSchema(target, root, seen),
    ...value,
  }
}

export function schemaProperty(value: JsonValue | undefined, key: string) {
  return isJsonObject(value) && Object.hasOwn(value, key)
    ? value[key]
    : undefined
}

export function fieldKind(schema: JsonObject) {
  const { type } = schema
  if (typeof type === 'string')
    return type
  if (Array.isArray(type))
    return type.find(item => typeof item === 'string' && item !== 'null') ?? 'null'
  if (isJsonObject(schema.properties))
    return 'object'
  if (schema.items != null)
    return 'array'
  return 'json'
}

export function defaultFieldValue(schema: JsonObject): JsonValue {
  if (Object.hasOwn(schema, 'default'))
    return schema.default!
  if (Array.isArray(schema.enum) && schema.enum.length)
    return schema.enum[0]!
  switch (fieldKind(schema)) {
    case 'object': return {}
    case 'array': return []
    case 'boolean': return false
    case 'integer':
    case 'number': return typeof schema.minimum === 'number'
      ? schema.minimum
      : 0
    case 'string': return ''
    default: return null
  }
}

export function statusLabel(status: string) {
  const labels: Record<string, string> = {
    RUNNING: t('生成中'),
    SUCCEEDED: t('已生成'),
    FAILED: t('生成失败'),
    DRAFT: t('草稿'),
    PUBLISHED: t('已发布'),
  }
  return labels[status] ?? status
}

export function generationError(code: string) {
  const labels: Record<string, string> = {
    RENDER_FAILED: t('文档渲染失败'),
    STORAGE_UNAVAILABLE: t('暂时无法存储生成文件'),
    GENERATION_TIMEOUT: t('生成超时'),
    GENERATION_OUTPUT_CONFLICT: t('成品文件冲突'),
  }
  return labels[code]
    ? `${labels[code]} (${code})`
    : code
}
