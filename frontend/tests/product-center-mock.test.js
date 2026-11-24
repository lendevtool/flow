import { Buffer } from 'node:buffer'
import { Readable } from 'node:stream'
import { beforeEach, describe, expect, it } from 'vitest'
import { createMockMiddleware } from '../mock/server.js'

const base = '/workbench/products'
let middleware

beforeEach(() => {
  middleware = createMockMiddleware()
})

async function api(path, { token, method = 'GET', body } = {}) {
  const chunks = body === undefined
    ? []
    : [
        Buffer.from(JSON.stringify(body)),
      ]
  const incoming = Object.assign(Readable.from(chunks), {
    url: `/api${path}`,
    method,
    headers: token
      ? { authorization: `Bearer ${token}` }
      : {},
  })
  const result = {}
  await middleware(incoming, {
    writeHead(status) { result.status = status },
    end(content) { Object.assign(result, JSON.parse(content)) },
  }, () => {})
  return result
}

async function login() {
  const result = await api('/auth/login', {
    method: 'POST',
    body: { username: 'demo', password: 'FlowDemo123', loginMethod: 'LOCAL' },
  })
  return result.data.accessToken
}

describe('商品中心 Mock', () => {
  it('在工作台提供商品列表和可直接访问的详情路由', async () => {
    const token = await login()
    const { data } = await api('/role/permissions/tree', { token })
    const menu = data.find(item => item.code === 'Workbench').children.find(item => item.code === 'ProductCenter')
    expect(menu).toMatchObject({
      path: '/workbench/products',
      children: [
        { code: 'ProductCenterDetail', path: '/workbench/products/:id', show: false },
      ],
    })
    const list = await api(`${base}?pageNo=2&pageSize=2`, { token })
    expect(list).toMatchObject({
      status: 200,
      data: {
        pageData: [
          { code: 'P-1003' },
          { code: 'P-1004' },
        ],
      },
    })
    expect(await api(`${base}/1`)).toMatchObject({ status: 401 })
    expect(await api(`${base}/9000`, { token })).toMatchObject({ status: 404 })
  })

  it('演示商品按默认的 20 条分页，末页可打开商品详情', async () => {
    const token = await login()
    const first = await api(`${base}?pageNo=1&pageSize=20`, { token })
    const last = await api(`${base}?pageNo=2&pageSize=20`, { token })
    expect(first.data.pageData).toHaveLength(20)
    expect(last.data.pageData.length).toBeGreaterThan(0)
    const rows = [
      ...first.data.pageData,
      ...last.data.pageData,
    ]
    expect(rows).toHaveLength(first.data.total)
    expect(new Set(rows.map(row => row.id)).size).toBe(first.data.total)
    const product = last.data.pageData[0]
    expect(await api(`${base}/${product.id}`, { token })).toMatchObject({
      status: 200,
      data: { id: product.id, code: product.code },
    })
  })

  it('编码和库存支持分页，编辑第二页编码后可从该页读到更新', async () => {
    const token = await login()
    const codes = `${base}/1/records/codes`
    const firstPage = await api(`${codes}?pageNo=1&pageSize=20`, { token })
    const secondPageUrl = `${codes}?pageNo=2&pageSize=20`
    const secondPage = await api(secondPageUrl, { token })
    expect(firstPage.status).toBe(200)
    expect(firstPage.data.pageData).toHaveLength(20)
    expect(secondPage.data.pageData.length).toBeGreaterThan(0)
    expect(secondPage.data.total).toBe(firstPage.data.total)
    const row = secondPage.data.pageData[0]
    expect(firstPage.data.pageData.some(item => item.id === row.id)).toBe(false)

    const { data: product } = await api(`${base}/1`, { token })
    const values = { ...row.values, source: '翻页后的修改' }
    expect(await api(`${codes}/${row.id}`, {
      token,
      method: 'PATCH',
      body: { values, version: product.version },
    })).toMatchObject({ status: 200 })
    expect((await api(secondPageUrl, { token })).data.pageData[0]).toEqual({ id: row.id, values })

    const inventory = await api(`${base}/1/records/inventory?pageNo=2&pageSize=20`, { token })
    expect(inventory.data.pageData.length).toBeGreaterThan(0)
    expect(await api(`${base}/3/records/codes?pageNo=1&pageSize=20`, { token })).toMatchObject({
      status: 200,
      data: { pageData: [], total: 0 },
    })
    expect(await api(codes)).toMatchObject({ status: 401 })
    expect(await api(`${base}/1/records/missing`, { token })).toMatchObject({ status: 404 })
  })

  it('桌面无线充电器的七个明细接口返回独立的演示数据', async () => {
    const token = await login()
    const { data: product } = await api(`${base}/2`, { token })
    expect(product.code).toBe('P-1002')
    const variants = new Set(product.records.skus.map(row => row.id))
    for (const tab of [
      'codes',
      'skus',
      'prices',
      'suppliers',
      'inventory',
      'channels',
      'files',
    ]) {
      const result = await api(`${base}/2/records/${tab}?pageNo=1&pageSize=20`, { token })
      expect(result.status, tab).toBe(200)
      expect(result.data.total, tab).toBeGreaterThan(0)
      expect(result.data.pageData, tab).toHaveLength(result.data.total)
      for (const { values } of result.data.pageData) {
        if (values.skuId != null)
          expect(variants.has(values.skuId), tab).toBe(true)
      }
    }
  })

  it('保存基本信息和物流后，同步反映到商品详情和列表', async () => {
    const token = await login()
    const product = (await api(`${base}/1`, { token })).data
    const basic = {
      name: '保温杯 500 mL',
      category: product.category,
      brand: product.brand,
      unit: product.unit,
      status: product.status,
    }
    const first = await api(`${base}/1/basic`, {
      token,
      method: 'PATCH',
      body: { ...basic, version: product.version },
    })
    expect(first).toMatchObject({
      status: 200,
      data: { name: basic.name, packaging: product.packaging },
    })
    const packaging = { ...product.packaging, weightGrams: 350 }
    const second = await api(`${base}/1/packaging`, {
      token,
      method: 'PATCH',
      body: { ...packaging, version: first.data.version },
    })
    expect(second).toMatchObject({
      status: 200,
      data: {
        name: basic.name,
        packaging: { weightGrams: 350 },
      },
    })
    expect((await api(base, { token })).data.pageData[0].name).toBe(basic.name)
    expect((await api(`${base}/1`, { token })).data.packaging.weightGrams).toBe(350)
    expect(await api(`${base}/1/basic`, {
      token,
      method: 'PATCH',
      body: { ...basic, version: product.version },
    })).toMatchObject({ status: 409 })
  })

  it('同一商品可新增和逐条编辑同类型编码，其他商品的编码保持独立', async () => {
    const token = await login()
    const { data: product } = await api(`${base}/1`, { token })
    const otherCodes = (await api(`${base}/2/records/codes`, { token })).data.pageData
    const values = {
      type: '国际条码',
      code: '6901234567915',
      skuId: null,
      source: 'GS1',
      status: 'ENABLED',
    }
    const created = await api(`${base}/1/records/codes`, {
      token,
      method: 'POST',
      body: { values, version: product.version },
    })
    expect(created.status).toBe(200)
    const row = created.data.records.codes.find(row => row.values.code === values.code)
    expect(row.values.type).toBe('国际条码')
    expect(created.data.records.codes).toHaveLength(product.records.codes.length + 1)
    const changed = await api(`${base}/1/records/codes/${row.id}`, {
      token,
      method: 'PATCH',
      body: {
        values: { ...values, source: '仓库' },
        version: created.data.version,
      },
    })
    expect(changed.status).toBe(200)
    expect(changed.data.records.codes.find(item => item.id === row.id).values.source).toBe('仓库')
    expect((await api(`${base}/2/records/codes`, { token })).data.pageData).toEqual(otherCodes)
  })

  it('六个业务明细支持同一套新增和编辑流程', async () => {
    const token = await login()
    let { data: product } = await api(`${base}/1`, { token })
    const skuId = product.records.skus[0].id
    const values = {
      skus: {
        code: 'SKU-MOCK',
        specification: '蓝色',
        unit: '件',
        status: 'ENABLED',
      },
      prices: {
        skuId,
        priceType: '零售价',
        amount: 109,
        currency: 'CNY',
        effectiveDate: '2026-11-24',
      },
      suppliers: {
        name: '测试供货方',
        skuId,
        purchasePrice: 60,
        leadDays: 5,
      },
      inventory: {
        warehouse: '测试仓',
        skuId,
        available: 10,
        safetyStock: 3,
      },
      channels: { channel: '测试渠道', title: '测试商品', listingStatus: 'LISTED' },
      files: { fileName: '示例.jpg', purpose: '主图', order: 3 },
    }
    for (const [tab, entry] of Object.entries(values)) {
      const route = `${base}/1/records/${tab}`
      const create = await api(route, {
        token,
        method: 'POST',
        body: { values: entry, version: product.version },
      })
      expect(create.status, tab).toBe(200)
      const row = create.data.records[tab].at(-1)
      expect(row.values).toEqual(entry)
      const update = await api(`${route}/${row.id}`, {
        token,
        method: 'PATCH',
        body: { values: entry, version: create.data.version },
      })
      expect(update.status, tab).toBe(200)
      expect(update.data.records[tab].at(-1)).toEqual(row)
      product = update.data
    }
  })

  it('拒绝引用其他商品规格，保存失败时不改动版本或明细', async () => {
    const token = await login()
    const { data: product } = await api(`${base}/1`, { token })
    const result = await api(`${base}/1/records/codes`, {
      token,
      method: 'POST',
      body: {
        values: {
          type: '其他',
          code: 'NEW-01',
          skuId: 999,
          source: 'ERP',
          status: 'ENABLED',
        },
        version: product.version,
      },
    })
    expect(result.status).toBe(400)
    expect((await api(`${base}/1`, { token })).data).toEqual(product)
  })
})
