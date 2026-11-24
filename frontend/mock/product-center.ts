import type { ProductDetail, ProductField, ProductValues, RelatedTab } from '../src/views/workbench/product-center/model'
import { basicFields, isRelatedTab, packagingFields, relatedDefinitions } from '../src/views/workbench/product-center/model.ts'
import { at, checkVersion, collection, route } from './admin/common.ts'
import { mockTime } from './fixtures.ts'
import { productFixtures } from './product-center-fixtures.ts'
import { MockError, object, workflowPage } from './protocol.ts'

function readField({ key, label, optional, type, integer, options }: ProductField, body: Record<string, unknown>, product: ProductDetail) {
  const value = body[key]
  if ((value === null || value === undefined || (typeof value === 'string' && !value.trim())) && optional) {
    return type === 'sku' || type === 'number'
      ? null
      : ''
  }
  if (type === 'number' || type === 'sku') {
    if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || (integer && !Number.isSafeInteger(value)))
      throw new MockError(400, `请检查${label}`)
    if (type === 'sku' && !product.records.skus.some(row => row.id === value))
      throw new MockError(400, '请选择当前商品的有效 SKU')
    return value
  }
  if (typeof value !== 'string' || !value.trim() || value.length > 200 || (options && !Object.hasOwn(options, value)))
    throw new MockError(400, `请检查${label}`)
  if (type === 'date') {
    const date = new Date(`${value}T00:00:00Z`)
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || !Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== value)
      throw new MockError(400, `请检查${label}`)
  }
  return value.trim()
}

function readFields(fields: ProductField[], body: Record<string, unknown>, product: ProductDetail): ProductValues {
  return Object.fromEntries(fields.map(field => [
    field.key,
    readField(field, body, product),
  ]))
}

function relatedTab(key: string): RelatedTab {
  if (!isRelatedTab(key))
    throw new MockError(404, '商品明细不存在')
  return key
}

function checkDuplicate(product: ProductDetail, tab: RelatedTab, values: ProductValues, id?: number) {
  if (tab === 'codes' || tab === 'skus') {
    const exists = product.records[tab].some(item =>
      item.id !== id && String(item.values.code).toLowerCase() === String(values.code).toLowerCase()
      && (tab === 'skus' || item.values.type === values.type),
    )
    if (exists)
      throw new MockError(409, '商品中已存在相同的编码')
  }
}

export function productCenterRoutes() {
  const base = '/workbench/products'
  const products = collection(productFixtures())
  function changed(product: ProductDetail) {
    product.version++
    product.updatedAt = mockTime()
    return product
  }
  return [
    route('GET', base, ({ query }) => workflowPage(products.rows().map(({ packaging, records, version, ...summary }) => summary), query)),
    route('GET', at(`${base}/:id`), ({ params }) => products.get(params[1])),
    route('GET', at(`${base}/:id/records/:code`), ({ params, query }) =>
      workflowPage(products.get(params[1]).records[relatedTab(params[2])], query)),
    route('PATCH', at(`${base}/:id/basic`), (request) => {
      const product = products.get(request.params[1])
      checkVersion(request, product.version)
      Object.assign(product, readFields(basicFields, request.body, product))
      return changed(product)
    }),
    route('PATCH', at(`${base}/:id/packaging`), (request) => {
      const product = products.get(request.params[1])
      checkVersion(request, product.version)
      Object.assign(product.packaging, readFields(packagingFields, request.body, product))
      return changed(product)
    }),
    route('POST', at(`${base}/:id/records/:code`), (request) => {
      const product = products.get(request.params[1])
      checkVersion(request, product.version)
      const tab = relatedTab(request.params[2])
      const values = readFields(relatedDefinitions[tab].fields, object(request.body.values), product)
      checkDuplicate(product, tab, values)
      const rows = product.records[tab]
      const id = Math.max(0, ...rows.map(item => item.id)) + 1
      rows.push({ id, values })
      return changed(product)
    }),
    route('PATCH', at(`${base}/:id/records/:code/:id`), (request) => {
      const product = products.get(request.params[1])
      checkVersion(request, product.version)
      const tab = relatedTab(request.params[2])
      const record = product.records[tab].find(item => item.id === Number(request.params[3]))
      if (!record)
        throw new MockError(404, '商品明细不存在')
      const values = readFields(relatedDefinitions[tab].fields, object(request.body.values), product)
      checkDuplicate(product, tab, values, record.id)
      record.values = values
      return changed(product)
    }),
  ]
}
