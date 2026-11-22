import type { DataType, FieldForm } from './model'
import { request } from '@/utils'

interface Response<T> {
  data: T
}

export interface FieldDefinition {
  id: number
  config: FieldForm
  createdBy: number | null
  createdAt: string
  updatedBy: number | null
  updatedAt: string
}

export type ValidationResult
  = | { valid: true, code: null, message: null }
    | { valid: false, code: string, message: string }

export interface Option {
  code: string
  name: string
  nameEn: string | null
  enable: boolean
}

export interface Dictionary extends Option {
  id: number
}

export interface Metadata {
  types: Option[]
  formats: Option[]
  characters: Option[]
}

interface Query {
  pageNo: number
  pageSize: number
  keyword: string
  dataType: DataType | null
  enable: boolean | null
}

const path = '/field-definition'

export default {
  page: (params: Query): Promise<Response<{ pageData: FieldDefinition[], total: number }>> => request.get(path, { params }),
  metadata: (): Promise<Response<Metadata>> => request.get(`${path}/metadata`),
  dictionaries: (keyword: string, selectedId: number | null): Promise<Response<Dictionary[]>> =>
    request.get(`${path}/dictionaries`, {
      params: { keyword, selectedId },
    }),
  options: (id: number): Promise<Response<Option[]>> => request.get(`${path}/dictionaries/${id}/options`),
  validate: (id: number, value: unknown, signal: AbortSignal): Promise<Response<ValidationResult>> =>
    request.post(`${path}/${id}/validate`, { value }, { signal }),
  create: (data: FieldForm) => request.post(path, data),
  update: (id: number, data: FieldForm) => request.put(`${path}/${id}`, data),
  remove: (id: number) => request.delete(`${path}/${id}`),
}
