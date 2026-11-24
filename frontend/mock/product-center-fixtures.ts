import type { Packaging, ProductDetail, ProductRecord, ProductSummary, ProductValues } from '../src/views/workbench/product-center/model'

const products: ProductSummary[] = [
  {
    id: 1,
    code: 'P-1001',
    name: '便携保温杯 480 mL',
    category: '家居用品',
    brand: '山岚',
    status: 'ENABLED',
    unit: '件',
    updatedAt: '2026-11-24T10:20:00+08:00',
  },
  {
    id: 2,
    code: 'P-1002',
    name: '桌面无线充电器',
    category: '数码配件',
    brand: '北岸',
    status: 'ENABLED',
    unit: '件',
    updatedAt: '2026-11-23T14:05:00+08:00',
  },
  {
    id: 3,
    code: 'P-1003',
    name: '轻量通勤背包',
    category: '箱包',
    brand: '路集',
    status: 'DRAFT',
    unit: '件',
    updatedAt: '2026-11-22T09:00:00+08:00',
  },
  {
    id: 4,
    code: 'P-1004',
    name: '天然棉毛巾套装',
    category: '家居用品',
    brand: '山岚',
    status: 'DISABLED',
    unit: '套',
    updatedAt: '2026-11-20T16:42:00+08:00',
  },
]

const moreProducts = [
  ['不锈钢焖烧罐', '家居用品', '山岚'],
  ['硅胶折叠水杯', '家居用品', '山岚'],
  ['耐热玻璃随手杯', '家居用品', '山岚'],
  ['双层便当盒', '家居用品', '山岚'],
  ['厨房电子秤', '家居用品', '山岚'],
  ['折叠收纳箱', '家居用品', '山岚'],
  ['磁吸充电宝', '数码配件', '北岸'],
  ['多口快充适配器', '数码配件', '北岸'],
  ['编织充电线', '数码配件', '北岸'],
  ['可调节平板支架', '数码配件', '北岸'],
  ['无线静音鼠标', '数码配件', '北岸'],
  ['便携蓝牙键盘', '数码配件', '北岸'],
  ['防泼水电脑包', '箱包', '路集'],
  ['轻便斜挎包', '箱包', '路集'],
  ['旅行洗漱包', '箱包', '路集'],
  ['折叠旅行袋', '箱包', '路集'],
  ['登机行李箱', '箱包', '路集'],
  ['户外轻便腰包', '箱包', '路集'],
  ['吸水干发帽', '家居用品', '山岚'],
  ['纯棉浴巾', '家居用品', '山岚'],
] as const

const inventoryWarehouses = [
  '上海备货仓',
  '杭州交付仓',
  '南京周转仓',
  '苏州配送仓',
  '深圳零售仓',
  '广州电商仓',
  '厦门区域仓',
  '武汉中心仓',
  '成都备货仓',
  '西安集货仓',
  '北京交付仓',
]

const packaging: Packaging[] = [
  {
    weightGrams: 310,
    dimensions: '7 × 7 × 21 cm',
    packagingType: '纸盒',
    transportConditions: '常温',
  },
  {
    weightGrams: 190,
    dimensions: '12 × 12 × 4 cm',
    packagingType: '纸盒',
    transportConditions: '常温',
  },
  {
    weightGrams: 520,
    dimensions: '31 × 14 × 43 cm',
    packagingType: '防尘袋',
    transportConditions: '常温',
  },
  {
    weightGrams: 410,
    dimensions: '28 × 18 × 6 cm',
    packagingType: '纸袋',
    transportConditions: '常温',
  },
]

const emptyPackaging: Packaging = {
  weightGrams: null,
  dimensions: '',
  packagingType: '',
  transportConditions: '',
}

function records(...rows: ProductValues[]): ProductRecord[] {
  return rows.map((values, index) => ({ id: index + 1, values }))
}

function chargerRecords(): ProductDetail['records'] {
  return {
    codes: records(
      {
        type: 'ERP 物料码',
        code: 'ERP-WC-15-BLK',
        skuId: 1,
        source: 'ERP',
        status: 'ENABLED',
      },
      {
        type: 'ERP 物料码',
        code: 'ERP-WC-15-WHT',
        skuId: 2,
        source: 'ERP',
        status: 'ENABLED',
      },
    ),
    skus: records(
      {
        code: 'SKU-WC-15-BLK',
        specification: '石墨黑 · 15 W',
        unit: '件',
        status: 'ENABLED',
      },
      {
        code: 'SKU-WC-15-WHT',
        specification: '云雾白 · 15 W',
        unit: '件',
        status: 'ENABLED',
      },
    ),
    prices: records(
      {
        skuId: 1,
        priceType: '零售价',
        amount: 119,
        currency: 'CNY',
        effectiveDate: '2026-11-01',
      },
      {
        skuId: 2,
        priceType: '零售价',
        amount: 119,
        currency: 'CNY',
        effectiveDate: '2026-11-01',
      },
    ),
    suppliers: records(
      {
        name: '海汐电子',
        skuId: 1,
        purchasePrice: 55,
        leadDays: 7,
      },
      {
        name: '深湾数码',
        skuId: 2,
        purchasePrice: 57,
        leadDays: 10,
      },
    ),
    inventory: records(
      {
        warehouse: '华东仓',
        skuId: 1,
        available: 180,
        safetyStock: 25,
      },
      {
        warehouse: '华南仓',
        skuId: 2,
        available: 96,
        safetyStock: 15,
      },
    ),
    channels: records(
      { channel: '品牌商城', title: '北岸桌面无线充电器 15 W', listingStatus: 'LISTED' },
      { channel: '线下门店', title: '桌面无线充电器 15 W', listingStatus: 'PENDING' },
    ),
    files: records(
      { fileName: '无线充电器主图.jpg', purpose: '商品主图', order: 1 },
      { fileName: '无线充电器说明书.pdf', purpose: '使用说明', order: 2 },
    ),
  }
}

export function productFixtures(): ProductDetail[] {
  const additionalProducts = moreProducts.map(([name, category, brand], index): ProductSummary => ({
    id: products.length + index + 1,
    code: `P-${1001 + products.length + index}`,
    name,
    category,
    brand,
    status: 'ENABLED',
    unit: '件',
    updatedAt: '2026-11-20T09:00:00+08:00',
  }))
  return [
    ...products,
    ...additionalProducts,
  ].map((product, index) => ({
    ...product,
    version: 1,
    packaging: {
      ...(packaging[index] ?? emptyPackaging),
    },
    records: product.id === 1
      ? {
          codes: records(
            {
              type: '国际条码',
              code: '6901234567892',
              skuId: 1,
              source: 'GS1',
              status: 'ENABLED',
            },
            {
              type: '国际条码',
              code: '6901234567908',
              skuId: 2,
              source: 'GS1',
              status: 'ENABLED',
            },
            {
              type: 'ERP 物料码',
              code: 'ERP-MG-0480',
              skuId: null,
              source: 'ERP',
              status: 'ENABLED',
            },
            {
              type: '旧商品编码',
              code: 'MUG-480-A',
              skuId: null,
              source: '旧商品系统',
              status: 'ENABLED',
            },
            ...Array.from({ length: 21 }, (_, index) => ({
              type: 'ERP 物料码',
              code: `ERP-MG-0480-${String(index + 1).padStart(2, '0')}`,
              skuId: (index % 2) + 1,
              source: 'ERP',
              status: 'ENABLED',
            })),
          ),
          skus: records(
            {
              code: 'SKU-480-BLK',
              specification: '曜石黑 · 480 mL',
              unit: '件',
              status: 'ENABLED',
            },
            {
              code: 'SKU-480-WHT',
              specification: '米白 · 480 mL',
              unit: '件',
              status: 'ENABLED',
            },
          ),
          prices: records(
            {
              skuId: 1,
              priceType: '零售价',
              amount: 129,
              currency: 'CNY',
              effectiveDate: '2026-11-01',
            },
            {
              skuId: 2,
              priceType: '零售价',
              amount: 129,
              currency: 'CNY',
              effectiveDate: '2026-11-01',
            },
          ),
          suppliers: records(
            {
              name: '青川日用品',
              skuId: 1,
              purchasePrice: 56,
              leadDays: 7,
            },
            {
              name: '川禾工贸',
              skuId: 2,
              purchasePrice: 58,
              leadDays: 10,
            },
          ),
          inventory: records(
            {
              warehouse: '华东仓',
              skuId: 1,
              available: 240,
              safetyStock: 30,
            },
            {
              warehouse: '华南仓',
              skuId: 2,
              available: 116,
              safetyStock: 20,
            },
            ...inventoryWarehouses.flatMap((warehouse, index) =>
              [1, 2].map(skuId => ({
                warehouse,
                skuId,
                available: 72 + index * 12 + skuId * 6,
                safetyStock: 12,
              })),
            ),
          ),
          channels: records(
            { channel: '品牌商城', title: '山岚便携保温杯 480 mL', listingStatus: 'LISTED' },
            { channel: '线下门店', title: '便携保温杯 480 mL', listingStatus: 'PENDING' },
          ),
          files: records(
            { fileName: '保温杯主图.jpg', purpose: '商品主图', order: 1 },
            { fileName: '使用说明.pdf', purpose: '使用说明', order: 2 },
          ),
        }
      : product.id === 2
        ? chargerRecords()
        : {
            codes: [],
            skus: [],
            prices: [],
            suppliers: [],
            inventory: [],
            channels: [],
            files: [],
          },
  }))
}
