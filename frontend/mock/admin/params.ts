import { fieldDictionaryFixtures } from '../field-fixtures.ts'
import { mockTime } from '../fixtures.ts'
import { MockError, workflowPage } from '../protocol.ts'
import { demoUser } from '../session.ts'
import { at, collection, ids, matchesAny, number, route, text, unique } from './common.ts'

interface Parameter {
  id: number
  code: string
  name: string
  sort: number
  [key: string]: unknown
}

export function createParams() {
  const base = '/param/groups'
  const metadata = () => ({
    updatedAt: mockTime(),
    updatedByName: demoUser.name,
  })
  const groups = collection<Parameter>(fieldDictionaryFixtures.map(({ items, ...group }, index) => ({
    ...group,
    sort: index + 1,
    fieldConfig: { fixedFields: {}, extraFields: [] },
    ...metadata(),
  })))
  const seedItems = fieldDictionaryFixtures.flatMap(({ id, items }) =>
    items.map(([code, name, nameEn, enable = true], index) => ({
      groupId: id,
      code,
      name,
      nameEn,
      enable,
      sort: index + 1,
    })),
  )
  const items = collection<Parameter>(seedItems.map((item, index) => ({
    ...item,
    id: index + 1,
    extra: {},
    ...metadata(),
  })))
  const order = (rows: Parameter[]) => rows.sort((a, b) => a.sort - b.sort || a.id - b.id)
  const groupItems = (groupId: string) => {
    const group = groups.get(groupId)
    return order(items.rows().filter(row => row.groupId === group.id))
  }
  function getItem(groupId: string, id: string) {
    const row = groupItems(groupId).find(item => item.id === Number(id))
    if (!row)
      throw new MockError(404, '参数不存在')
    return row
  }
  function search(rows: Parameter[], query: URLSearchParams) {
    return workflowPage(rows.filter(row => matchesAny(query.get('keyword'), row.code, row.name, row.nameEn)), query)
  }
  function changes(body: Record<string, unknown>, rows: Parameter[], current?: Parameter) {
    const code = text(body.code, current?.code)
    unique(rows.filter(row => row !== current), code, row => row.code)
    return {
      ...body,
      ...metadata(),
      code,
      name: text(body.name, current?.name),
      sort: number(body.sort, current?.sort ?? rows.length + 1),
    }
  }
  function reorder(rows: Parameter[], desired: unknown) {
    const keys = ids(desired)
    if (keys.length !== rows.length || rows.some(row => !keys.includes(row.id)))
      throw new MockError(409, '排序列表已变化')
    rows.forEach((row) => {
      row.sort = keys.indexOf(row.id) + 1
    })
  }
  const routes = [
    route('GET', base, ({ query }) => search(order(groups.rows()), query)),
    route('GET', `${base}/order`, () => order(groups.rows())),
    route('PATCH', `${base}/sort`, ({ body }) => reorder(groups.rows(), body.ids)),
    route('GET', at(`${base}/:id`), ({ params }) => groups.get(params[1])),
    route('POST', base, ({ body }) => groups.create({
      enable: true,
      fieldConfig: {},
      ...changes(body, groups.rows()),
    })),
    route('PATCH', at(`${base}/:id`), ({ params, body }) => {
      const group = groups.get(params[1])
      return Object.assign(group, changes(body, groups.rows(), group), { id: group.id })
    }),
    route('DELETE', at(`${base}/:id`), ({ params }) => {
      if (groupItems(params[1]).length)
        throw new MockError(409, '请先删除组内的参数')
      groups.remove(params[1])
    }),
    route('GET', at(`${base}/:id/items`), ({ params, query }) => search(groupItems(params[1]), query)),
    route('GET', at(`${base}/:id/items/order`), ({ params }) => groupItems(params[1])),
    route('GET', at(`${base}/:id/items/:id`), ({ params }) => getItem(params[1], params[2])),
    route('POST', at(`${base}/:id/items`), ({ params, body }) => items.create({
      enable: true,
      extra: {},
      ...changes(body, groupItems(params[1])),
      groupId: Number(params[1]),
    })),
    route('PATCH', at(`${base}/:id/items/:id`), ({ params, body }) => {
      const row = getItem(params[1], params[2])
      return Object.assign(row, changes(body, groupItems(params[1]), row), { id: row.id, groupId: row.groupId })
    }),
    route('DELETE', at(`${base}/:id/items/:id`), ({ params }) => items.remove(getItem(params[1], params[2]).id)),
    route('PATCH', at(`${base}/:id/sort`), ({ params, body }) => reorder(groupItems(params[1]), body.ids)),
    route('POST', '/param/cache/refresh', () => null),
  ]
  return { groups, groupItems, routes }
}

export type MockParams = ReturnType<typeof createParams>
