import type { MockRequest, MockRoute } from '../protocol'
import { get, matches, MockError, workflowPage } from '../protocol.ts'

export function route(method: string, path: string | RegExp, handle: MockRoute['handle']): MockRoute {
  return { method, path, handle }
}

export function at(path: string) {
  return new RegExp(`^${path.replaceAll(':id', '(\\d+)').replaceAll(':code', '([^/]+)')}$`)
}

export function page<T>(items: T[], query: URLSearchParams) {
  const { pageData, total } = workflowPage(items, query)
  return { records: pageData, total }
}

export function text(value: unknown, fallback = '') {
  return typeof value === 'string'
    ? value
    : fallback
}

export function number(value: unknown, fallback: number) {
  return typeof value === 'number' && Number.isSafeInteger(value)
    ? value
    : fallback
}

export function ids(value: unknown): number[] {
  return Array.isArray(value)
    ? [
        ...new Set(value.filter((id): id is number => Number.isSafeInteger(id) && id > 0)),
      ]
    : []
}

export function bool(value: unknown, fallback: boolean) {
  return typeof value === 'boolean'
    ? value
    : fallback
}

export function matchesAny(filter: string | null, ...values: unknown[]) {
  return values.some(value => matches(String(value ?? ''), filter))
}

export function enabled(value: boolean, filter: string | null) {
  return !filter || value === (filter === '1' || filter === 'true')
}

export function unique<T>(items: Iterable<T>, code: string, select: (item: T) => string) {
  if (!code.trim())
    throw new MockError(400, '请输入编码')
  if (Array.from(items).some(item => select(item).toLowerCase() === code.toLowerCase()))
    throw new MockError(409, '编码已存在')
}

export function collection<T extends { id: number }>(items: T[]) {
  const records = new Map(items.map(row => [row.id, row]))
  let nextId = Math.max(0, ...items.map(row => row.id)) + 1
  return {
    rows: () => Array.from(records.values()),
    get: (id: string | number) => get(records, String(id)),
    has: (id: number) => records.has(id),
    create(values: Omit<T, 'id'>) {
      const row = { ...values, id: nextId++ } as T
      records.set(row.id, row)
      return row
    },
    remove: (id: string | number) => records.delete(Number(id)),
  }
}

export function checkVersion(request: MockRequest, current: number, key = 'version') {
  if (request.body[key] !== current)
    throw new MockError(409, '数据已变化，请刷新后重试')
}

export class MockFile {
  constructor(readonly content: string, readonly contentType: string, readonly filename?: string) {}
}
