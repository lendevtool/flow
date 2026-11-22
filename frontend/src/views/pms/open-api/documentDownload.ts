export type DocumentFormat = 'json' | 'markdown'

type JsonObject = Record<string, unknown>

const methods = new Set([
  'get',
  'put',
  'post',
  'delete',
  'options',
  'head',
  'patch',
  'trace',
])

function object(value: unknown): JsonObject {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as JsonObject
    : {}
}

function list(value: unknown): unknown[] {
  return Array.isArray(value)
    ? value
    : []
}

function text(value: unknown): string {
  return typeof value === 'string'
    ? value
    : ''
}

function inline(value: unknown): string {
  // 契约说明按文本写入标题和表格，避免 HTML、链接或换行改变文档结构。
  return text(value).replace(/[\r\n]+/g, ' ').replace(/[\\`*_[\]<>|]/g, '\\$&')
}

function jsonBlock(value: unknown): string {
  const json = JSON.stringify(value, null, 2) ?? 'null'
  const width = (json.match(/`+/g) ?? []).reduce((max, run) => Math.max(max, run.length + 1), 3)
  const fence = '`'.repeat(width)
  return `${fence}json\n${json}\n${fence}`
}

function mergeParameters(shared: unknown, own: unknown): JsonObject[] {
  const parameters = new Map<string, JsonObject>()
  for (const value of [
    ...list(shared),
    ...list(own),
  ]) {
    const parameter = object(value)
    parameters.set(text(parameter.$ref) || `${text(parameter.in)}:${text(parameter.name)}`, parameter)
  }
  return [
    ...parameters.values(),
  ]
}

function parameterRows(parameters: JsonObject[]): string[] {
  if (!parameters.length)
    return []
  const rows = [
    '#### 请求参数',
    '',
    '| 名称 | 位置 | 必填 | 类型 | 说明 |',
    '| --- | --- | --- | --- | --- |',
  ]
  for (const parameter of parameters) {
    const schema = object(parameter.schema)
    const type = text(schema.$ref) || text(schema.type) || list(schema.type).map(text).join(' / ')
    rows.push(`| ${inline(parameter.name || parameter.$ref)} | ${inline(parameter.in)} | ${parameter.required === true
      ? '是'
      : '否'} | ${inline(type)} | ${inline(parameter.description)} |`)
  }
  return rows
}

/** 将同一份已发布 OpenAPI 契约生成为 Markdown，保留请求、响应、引用模型和扩展字段。 */
export function openApiMarkdown(value: unknown): string {
  const document = object(value)
  if (!text(document.openapi) || !document.paths || typeof document.paths !== 'object' || Array.isArray(document.paths))
    throw new Error('Invalid OpenAPI document')

  const info = object(document.info)
  const lines = [
    `# ${inline(info.title) || 'Flow Open API'}`,
    '',
    `API 版本：${inline(info.version)} · OpenAPI：${inline(document.openapi)}`,
    '',
  ]
  if (info.description)
    lines.push(inline(info.description), '')
  lines.push('## 服务地址', '')
  for (const server of list(document.servers)) {
    const entry = object(server)
    lines.push(`- ${inline(entry.url)}${entry.description
      ? ` — ${inline(entry.description)}`
      : ''}`)
  }
  lines.push('', '## 鉴权方式', '', jsonBlock(object(document.components).securitySchemes ?? {}), '', '## 接口目录', '')

  for (const [path, value] of Object.entries(object(document.paths))) {
    const item = object(value)
    for (const [method, value] of Object.entries(item)) {
      if (!methods.has(method))
        continue
      const operation = object(value)
      const parameters = mergeParameters(item.parameters, operation.parameters)
      lines.push(`### ${method.toUpperCase()} ${inline(path)}`, '')
      if (operation.summary)
        lines.push(inline(operation.summary), '')
      if (operation.description)
        lines.push(inline(operation.description), '')
      if (operation['x-required-scope'])
        lines.push(`所需 Scope：${inline(operation['x-required-scope'])}`, '')
      lines.push(...parameterRows(parameters), '')
      // 完整保留媒体类型、必填性、错误响应、示例及 JSON Schema，供引用模型一并使用。
      lines.push('#### 请求与响应契约', '', jsonBlock({
        ...operation,
        parameters,
      }), '')
    }
  }
  lines.push('## 组件定义', '', jsonBlock(document.components ?? {}), '')
  // 保留根级安全要求、服务变量及其他扩展，以及路径共享配置。
  const { paths, components: _components, ...metadata } = document
  const sharedPaths = Object.fromEntries(Object.entries(object(paths)).map(([path, value]) => [
    path,
    Object.fromEntries(Object.entries(object(value)).filter(([key]) => !methods.has(key))),
  ]).filter(([, value]) => Object.keys(object(value)).length))
  lines.push('## 文档元数据', '', jsonBlock({ ...metadata, paths: sharedPaths }), '')
  return lines.join('\n')
}

export async function documentFile(source: Blob, format: DocumentFormat): Promise<{ blob: Blob, name: string }> {
  if (format === 'json')
    return { blob: source, name: 'flow-open-api-v1.json' }
  const markdown = openApiMarkdown(JSON.parse(await source.text()))
  return {
    blob: new Blob([markdown], { type: 'text/markdown;charset=utf-8' }),
    name: 'flow-open-api-v1.md',
  }
}
