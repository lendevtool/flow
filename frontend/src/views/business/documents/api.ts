import type { AxiosResponse } from 'axios'
import { isCancel } from 'axios'
import { t } from '@/i18n'
import { request } from '@/utils/http'
import { resolveResError } from '@/utils/http/helpers'

interface Response<T> {
  data: T
}

export type DimensionSlot = 1 | 2 | 3 | 4 | 5 | 6
export type DimensionValues = Partial<Record<DimensionSlot, string>>

export interface DocumentOption {
  id: number
  name: string
  enabled: boolean
}

export interface DocumentDimension {
  slot: DimensionSlot
  name: string
  inputType: 'TEXT' | 'SELECT'
  enabled: boolean
  options: DocumentOption[]
}

export interface DocumentOptions {
  departments: DocumentOption[]
  products: DocumentOption[]
  dimensions: DocumentDimension[]
}

export interface DocumentMetadata {
  name: string
  departmentId: number | null
  productId: number | null
  dimensions: DimensionValues
}

export interface DocumentVersion {
  id: number
  versionNo: string
  originalFileName: string
  downloadName: string
  contentType: string
  sizeBytes: number
  createdAt: string
  sftpPath?: string
}

export interface DocumentRecord extends DocumentMetadata {
  id: number
  sftpPath?: string
  latestVersion: DocumentVersion | null
  versions: DocumentVersion[]
  createdAt: string
  updatedAt: string
}

export type DocumentQuery = {
  pageNo: number
  pageSize: number
  keyword?: string
  departmentId?: number
  productId?: number
} & Partial<Record<`dimension${DimensionSlot}Value`, string>>

const prefix = '/documents'
const transferOptions = { timeout: 0 }
const inlineErrors = { needTip: false, headers: {} }

async function upload<T>(url: string, form: FormData): Promise<Response<T>> {
  try {
    return await request.post(url, form, {
      ...transferOptions,
      ...inlineErrors,
    })
  }
  catch (failure) {
    if (isCancel(failure))
      throw failure
    const code = failure && typeof failure === 'object' && 'code' in failure
      ? failure.code
      : undefined
    const body = failure && typeof failure === 'object' && 'error' in failure
      ? failure.error
      : undefined
    const message = body && typeof body === 'object' && 'code' in body && body.code === code
      && 'message' in body && typeof body.message === 'string'
      ? body.message
      : undefined

    if (code === 401 || code === 11007 || code === 11008)
      resolveResError(code, message ?? t('登录已过期，请重新登录'), true)
    else
      window.$message?.warning(uploadErrorMessage(code, message))
    throw failure
  }
}

function uploadErrorMessage(code: unknown, message: string | undefined) {
  switch (code) {
    case 'ERR_NETWORK':
      return t('上传未完成，请检查网络后重试')
    case 'ECONNABORTED':
    case 'ETIMEDOUT':
      return t('上传超时，请稍后重试')
    case 413:
      return message || t('文件太大，请选择较小的文件后重新上传')
    case 403:
    case 11003:
      return t('你没有上传文档的权限')
    case 400:
      return message || t('请检查所选文件和文档信息后重新上传')
    case 404:
      return t('找不到要更新的文档，请刷新列表后重试')
    case 409:
      return message || t('文档已发生变化，请刷新列表后重试')
    default:
      return t('暂时无法上传文件，请稍后重试')
  }
}

function attachmentName(disposition: string) {
  const encoded = /(?:^|;)\s*filename\*=utf-8'[^']*'([^;]+)/i.exec(disposition)?.[1]
  if (encoded) {
    try {
      return decodeURIComponent(encoded.trim())
    }
    catch {
      // 无法解码时使用普通文件名。
    }
  }
  const plain = /(?:^|;)\s*filename=(?:"((?:[^"\\]|\\.)*)"|([^;]+))/i.exec(disposition)
  return plain?.[1]?.replace(/\\(["\\])/g, '$1') || plain?.[2]?.trim()
}

export default {
  options: (): Promise<Response<DocumentOptions>> =>
    request.get(`${prefix}/options`, inlineErrors),
  page: (params: DocumentQuery, signal: AbortSignal): Promise<Response<{ records: DocumentRecord[], total: number }>> =>
    request.get(prefix, { ...inlineErrors, params, signal }),
  detail: (id: number, signal: AbortSignal): Promise<Response<DocumentRecord>> =>
    request.get(`${prefix}/${id}`, { ...inlineErrors, signal }),
  create: (metadata: DocumentMetadata, file: File): Promise<Response<DocumentRecord>> => {
    const form = new FormData()
    form.append('metadata', new Blob([
      JSON.stringify(metadata),
    ], { type: 'application/json' }))
    form.append('file', file)
    return upload(prefix, form)
  },
  update: (id: number, metadata: DocumentMetadata): Promise<Response<DocumentRecord>> =>
    request.put(`${prefix}/${id}`, metadata),
  addVersion: (id: number, file: File): Promise<Response<DocumentVersion>> => {
    const form = new FormData()
    form.append('file', file)
    return upload(`${prefix}/${id}/versions`, form)
  },
  async download(id: number, versionId?: number): Promise<{ file: Blob, name?: string }> {
    const config = {
      ...transferOptions,
      params: { versionId },
      responseType: 'blob' as const,
      keepAttachmentResponse: true,
    }
    const response = await request.get<Blob, AxiosResponse<Blob>>(`${prefix}/${id}/download`, config)
    const disposition = response.headers?.['content-disposition']
    if (!(response.data instanceof Blob) || typeof disposition !== 'string')
      throw new TypeError('Invalid document download response')
    return {
      file: response.data,
      name: attachmentName(disposition),
    }
  },
  remove: (id: number): Promise<Response<null>> => request.delete(`${prefix}/${id}`),
}
