import type { ProductDetail, ProductRecord, ProductSummary, ProductValues, RelatedTab } from './model'
import { request } from '@/utils'

interface Response<T> { data: T }
interface Page<T> { pageData: T[], total: number }
interface PageQuery { pageNo: number, pageSize: number }

const base = '/workbench/products'
const options = { needTip: false, headers: {} }

export default {
  list: (params: PageQuery): Promise<Response<Page<ProductSummary>>> =>
    request.get(base, { ...options, params }),
  detail: (id: number): Promise<Response<ProductDetail>> =>
    request.get(`${base}/${id}`, options),
  listRecords: (id: number, tab: RelatedTab, params: PageQuery): Promise<Response<Page<ProductRecord>>> =>
    request.get(`${base}/${id}/records/${tab}`, { ...options, params }),
  saveSection: (id: number, section: 'basic' | 'packaging', values: ProductValues, version: number): Promise<Response<ProductDetail>> =>
    request.patch(`${base}/${id}/${section}`, { ...values, version }, options),
  addRecord: (id: number, tab: RelatedTab, values: ProductValues, version: number): Promise<Response<ProductDetail>> =>
    request.post(`${base}/${id}/records/${tab}`, { values, version }, options),
  updateRecord: (id: number, tab: RelatedTab, recordId: number, values: ProductValues, version: number): Promise<Response<ProductDetail>> =>
    request.patch(`${base}/${id}/records/${tab}/${recordId}`, { values, version }, options),
}
