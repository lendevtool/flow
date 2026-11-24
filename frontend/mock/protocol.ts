export interface MockRequest {
  query: URLSearchParams
  params: RegExpMatchArray
  body: Record<string, unknown>
  token: string
}

export interface MockRoute {
  method: string
  path: string | RegExp
  access?: 'public'
  handle: (request: MockRequest) => unknown
}

export class MockError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

export function object(value: unknown): Record<string, unknown> {
  if (value && typeof value === 'object' && !Array.isArray(value))
    return Object.fromEntries(Object.entries(value))
  throw new MockError(400, '请求格式有误')
}

export function get<T>(records: Map<number, T>, id: string): T {
  const record = records.get(Number(id))
  if (record === undefined)
    throw new MockError(404, '记录不存在')
  return record
}

export function slicePage<T>(items: T[], page: string | null, size: string | null) {
  const integer = (value: string | null, fallback: number) => {
    const n = Number(value)
    return Number.isSafeInteger(n) && n > 0
      ? n
      : fallback
  }
  const take = Math.min(integer(size, 20), 200)
  const start = (integer(page, 1) - 1) * take
  return items.slice(start, start + take)
}

export function workflowPage<T>(items: T[], query: URLSearchParams) {
  return {
    pageData: slicePage(items, query.get('pageNo'), query.get('pageSize')),
    total: items.length,
  }
}

export function matches(text: string, filter: string | null) {
  return text.toLowerCase().includes(filter?.trim().toLowerCase() ?? '')
}

export function inRange(date: string, from: string | null, to: string | null) {
  return (!from || date >= from) && (!to || date <= to)
}
