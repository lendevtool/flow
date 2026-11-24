export const productStatuses = {
  ENABLED: '启用',
  DRAFT: '草稿',
  DISABLED: '停用',
} as const

export const openingModes = {
  page: '整页打开',
  modal: '弹窗打开',
  drawer: '右侧抽屉',
  split: '左右分栏',
} as const

export type ProductOpeningMode = keyof typeof openingModes
export type ProductStatus = keyof typeof productStatuses

export function productOpeningMode(value: string): ProductOpeningMode {
  return Object.hasOwn(openingModes, value)
    ? value as ProductOpeningMode
    : 'page'
}

type FieldValue = string | number | null
export type ProductValues = Record<string, FieldValue>
export interface ProductField {
  key: string
  label: string
  type?: 'number' | 'date' | 'sku' | 'select'
  optional?: boolean
  integer?: boolean
  options?: Readonly<Record<string, string>>
}

export interface ProductRecord {
  id: number
  values: ProductValues
}

export interface ProductSummary {
  id: number
  code: string
  name: string
  category: string
  brand: string
  unit: string
  status: ProductStatus
  updatedAt: string
}

export interface Packaging {
  weightGrams: number | null
  dimensions: string
  packagingType: string
  transportConditions: string
}

export interface ProductDetail extends ProductSummary {
  version: number
  packaging: Packaging
  records: Record<RelatedTab, ProductRecord[]>
}

export const basicFields: ProductField[] = [
  { key: 'name', label: '商品名称' },
  { key: 'category', label: '商品分类' },
  { key: 'brand', label: '品牌', optional: true },
  { key: 'unit', label: '计量单位' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: productStatuses,
  },
]

export const packagingFields: ProductField[] = [
  {
    key: 'weightGrams',
    label: '包装重量（克）',
    type: 'number',
    optional: true,
  },
  { key: 'dimensions', label: '包装尺寸', optional: true },
  { key: 'packagingType', label: '包装方式', optional: true },
  { key: 'transportConditions', label: '运输条件', optional: true },
]

interface RelatedDefinition {
  label: string
  fields: ProductField[]
}

const listingStatuses = { LISTED: '已上架', PENDING: '待上架', UNLISTED: '已下架' }

export const relatedDefinitions = {
  codes: {
    label: '商品编码',
    fields: [
      { key: 'type', label: '编码类型' },
      { key: 'code', label: '编码值' },
      {
        key: 'skuId',
        label: '关联 SKU',
        type: 'sku',
        optional: true,
      },
      { key: 'source', label: '来源', optional: true },
      {
        key: 'status',
        label: '状态',
        type: 'select',
        options: productStatuses,
      },
    ],
  },
  skus: {
    label: '销售规格',
    fields: [
      { key: 'code', label: 'SKU 编码' },
      { key: 'specification', label: '规格' },
      { key: 'unit', label: '销售单位' },
      {
        key: 'status',
        label: '状态',
        type: 'select',
        options: productStatuses,
      },
    ],
  },
  prices: {
    label: '价格',
    fields: [
      { key: 'skuId', label: '适用规格', type: 'sku' },
      { key: 'priceType', label: '价格类型' },
      { key: 'amount', label: '金额', type: 'number' },
      { key: 'currency', label: '币种' },
      { key: 'effectiveDate', label: '生效日期', type: 'date' },
    ],
  },
  suppliers: {
    label: '供应商',
    fields: [
      { key: 'name', label: '供应商' },
      { key: 'skuId', label: '供货规格', type: 'sku' },
      { key: 'purchasePrice', label: '采购价（元）', type: 'number' },
      {
        key: 'leadDays',
        label: '交期（天）',
        type: 'number',
        integer: true,
      },
    ],
  },
  inventory: {
    label: '库存',
    fields: [
      { key: 'warehouse', label: '仓库' },
      { key: 'skuId', label: '商品规格', type: 'sku' },
      {
        key: 'available',
        label: '可用数量',
        type: 'number',
        integer: true,
      },
      {
        key: 'safetyStock',
        label: '安全库存',
        type: 'number',
        integer: true,
      },
    ],
  },
  channels: {
    label: '销售渠道',
    fields: [
      { key: 'channel', label: '渠道' },
      { key: 'title', label: '展示标题' },
      {
        key: 'listingStatus',
        label: '上架状态',
        type: 'select',
        options: listingStatuses,
      },
    ],
  },
  files: {
    label: '图片与附件',
    fields: [
      { key: 'fileName', label: '文件名' },
      { key: 'purpose', label: '用途' },
      {
        key: 'order',
        label: '排序',
        type: 'number',
        integer: true,
      },
    ],
  },
} satisfies Record<string, RelatedDefinition>

export type RelatedTab = keyof typeof relatedDefinitions

export function isRelatedTab(value: string): value is RelatedTab {
  return Object.hasOwn(relatedDefinitions, value)
}

export const relatedTabs = Object.keys(relatedDefinitions).filter(isRelatedTab)

export function formValues(fields: ProductField[], source: object = {}): ProductValues {
  const values = new Map(Object.entries(source))
  return Object.fromEntries(fields.map(({ key, options }) => [
    key,
    values.get(key) ?? (options
      ? Object.keys(options)[0]
      : null),
  ]))
}
