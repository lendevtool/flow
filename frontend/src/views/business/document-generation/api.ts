import type { AxiosResponse } from 'axios'
import type { JsonObject } from './input'
import { t } from '@/i18n'
import { request } from '@/utils/http'
import { isJsonObject } from './input'

interface Response<T> {
  data: T
}

interface Page<T> {
  records: T[]
  total: number
}

interface PageQuery {
  pageNo: number
  pageSize: number
}

export interface TemplateSummary {
  id: string
  code: string
  name: string
  description: string | null
  enabled: boolean
  currentVersionId: string | null
}

export interface TemplateSample {
  code: string
  name: string
  input: JsonObject
}

export interface CurrentTemplate {
  templateId: string
  name: string
  versionId: string
  versionNo: number
  inputSchema: JsonObject
  exampleJson: JsonObject | null
  samples: TemplateSample[]
}

export interface TemplateVersion {
  id: string
  templateId: string
  versionNo: number
  status: 'DRAFT' | 'PUBLISHED'
  templateFormat: string
  inputSchema: JsonObject
  exampleJson: JsonObject | null
  renderConfig: JsonObject | null
  samples: TemplateSample[]
  documentId: string | null
  documentVersionId: string | null
  etag: string
  updatedAt: string
}

export type GenerationStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export interface Generation {
  id: string
  templateId: string
  templateVersionId: string
  templateName: string
  versionNo: number
  status: GenerationStatus
  errorCode: string | null
  createdAt: string
  finishedAt: string | null
  hasInput: boolean
  hasFile: boolean
}

export interface GenerationQuery extends PageQuery {
  templateId?: string
  status?: GenerationStatus
  startDate?: string
  endDate?: string
}

export interface TemplateQuery extends PageQuery {
  keyword?: string
  enabled?: boolean
  published?: boolean
}

export type DocumentGenerationFileType = 'TEMPLATE_VERSION' | 'GENERATION'

interface DocumentGenerationFileMetadata {
  templateId: string
  templateVersionId: string
  templateName: string
  versionNo: number
  originalFileName: string
  sftpPath: string
  sizeBytes: number
  createdAt: string
}

export type DocumentGenerationFile = DocumentGenerationFileMetadata & (
  | { type: 'TEMPLATE_VERSION', generationId: null, status: 'DRAFT' | 'PUBLISHED' }
  | { type: 'GENERATION', generationId: string, status: 'SUCCEEDED' }
)

export interface DocumentGenerationFileQuery extends PageQuery {
  type: DocumentGenerationFileType
  keyword?: string
}

export interface DocxFile {
  file: Blob
  name: string
}

type VersionConfig = Pick<TemplateVersion, 'inputSchema' | 'exampleJson' | 'renderConfig' | 'samples'>
type TemplateDetails = Pick<TemplateSummary, 'name' | 'description' | 'enabled'>

const prefix = '/document-generation'
const templates = `${prefix}/templates`
const admin = `${prefix}/admin/templates`
const generations = `${prefix}/generations`
const inlineErrors = { needTip: false, headers: {} }
const longRequest = { timeout: 90000, ...inlineErrors }
const attachment = { ...longRequest, responseType: 'blob' as const, keepAttachmentResponse: true }

function versionUrl(templateId: string, versionId?: string) {
  const path = `${admin}/${templateId}/versions`
  return versionId === undefined
    ? path
    : `${path}/${versionId}`
}

function ifMatch(etag: string) {
  return { 'If-Match': etag }
}

function docx(response: AxiosResponse<Blob>, fallback: string): DocxFile {
  if (!(response.data instanceof Blob))
    throw new TypeError(t('未收到 Word 文件'))
  const disposition = response.headers['content-disposition']
  let name = fallback
  if (typeof disposition === 'string') {
    const encoded = /(?:^|;)\s*filename\*=utf-8'[^']*'([^;]+)/i.exec(disposition)?.[1]
    const plain = /(?:^|;)\s*filename=(?:"((?:[^"\\]|\\.)*)"|([^;]+))/i.exec(disposition)
    name = plain?.[1]?.replace(/\\(["\\])/g, '$1') || plain?.[2]?.trim() || fallback
    if (encoded) {
      try {
        name = decodeURIComponent(encoded.trim())
      }
      catch {
        // 使用响应提供的普通文件名。
      }
    }
  }
  return { file: response.data, name }
}

export function errorText(failure: unknown, fallback: string) {
  if (failure instanceof Error)
    return failure.message || fallback
  if (!isJsonObject(failure))
    return fallback

  const body = isJsonObject(failure.error)
    ? failure.error
    : {}
  const data = isJsonObject(body.data)
    ? body.data
    : {}
  const issues = body.errors ?? data.errors
  const messages = Array.isArray(issues)
    ? issues.flatMap((issue) => {
        if (!isJsonObject(issue))
          return []
        const path = issue.path ?? issue.pointer
        const message = issue.message ?? issue.code
        return typeof message === 'string'
          ? [`${typeof path === 'string' && path
              ? `${path} · `
              : ''}${message}`]
          : []
      })
    : []
  const path = body.path ?? data.path
  const message = typeof failure.message === 'string'
    ? failure.message
    : fallback
  if (messages.length)
    return [message, ...messages].join('\n')
  return typeof path === 'string' && path
    ? `${path} · ${message}`
    : message
}

export function errorCode(failure: unknown) {
  return isJsonObject(failure)
    ? failure.code
    : undefined
}

export function hasBusinessError(failure: unknown, code: string) {
  if (!isJsonObject(failure) || !isJsonObject(failure.error) || !isJsonObject(failure.error.data))
    return false
  return failure.error.data.errorCode === code
}

const api = {
  files: (params: DocumentGenerationFileQuery, signal?: AbortSignal): Promise<Response<Page<DocumentGenerationFile>>> =>
    request.get(`${prefix}/files`, { ...inlineErrors, params, signal }),
  templates: (signal?: AbortSignal): Promise<Response<TemplateSummary[]>> =>
    request.get(templates, { ...inlineErrors, signal }),
  currentTemplate: (id: string, signal?: AbortSignal): Promise<Response<CurrentTemplate>> =>
    request.get(`${templates}/${id}/current`, { ...inlineErrors, signal }),
  adminTemplates: (params: TemplateQuery): Promise<Response<Page<TemplateSummary>>> =>
    request.get(admin, { ...inlineErrors, params }),
  createTemplate: (body: Pick<TemplateSummary, 'code' | 'name' | 'description'>): Promise<Response<{ template: TemplateSummary, version: TemplateVersion }>> =>
    request.post(admin, body, inlineErrors),
  updateTemplate: (id: string, body: Partial<TemplateDetails>): Promise<Response<TemplateSummary>> =>
    request.patch(`${admin}/${id}`, body, inlineErrors),
  versions: (templateId: string, signal?: AbortSignal): Promise<Response<TemplateVersion[]>> =>
    request.get(versionUrl(templateId), { ...inlineErrors, signal }),
  createVersion: (templateId: string, sourceVersionId?: string): Promise<Response<TemplateVersion>> =>
    request.post(versionUrl(templateId), { sourceVersionId }, inlineErrors),
  version: (templateId: string, id: string, signal?: AbortSignal): Promise<Response<TemplateVersion>> =>
    request.get(versionUrl(templateId, id), { ...inlineErrors, signal }),
  saveVersion: (version: TemplateVersion, config: VersionConfig): Promise<Response<TemplateVersion>> =>
    request.put(versionUrl(version.templateId, version.id), config, {
      ...inlineErrors,
      headers: ifMatch(version.etag),
    }),
  upload: (version: TemplateVersion, file: File): Promise<Response<TemplateVersion>> => {
    const form = new FormData()
    form.append('file', file)
    return request.put(`${versionUrl(version.templateId, version.id)}/file`, form, {
      ...longRequest,
      headers: ifMatch(version.etag),
    })
  },
  async templateFile(version: Pick<TemplateVersion, 'templateId' | 'id' | 'versionNo'>, signal?: AbortSignal): Promise<DocxFile> {
    const response = await request.get<Blob, AxiosResponse<Blob>>(`${versionUrl(version.templateId, version.id)}/file`, { ...attachment, signal })
    return docx(response, `template-${version.templateId}-v${version.versionNo}.docx`)
  },
  async testVersion(version: TemplateVersion, input: JsonObject): Promise<DocxFile> {
    const response = await request.post<Blob, AxiosResponse<Blob>>(`${versionUrl(version.templateId, version.id)}/test`, { input }, {
      ...attachment,
      headers: ifMatch(version.etag),
    })
    return docx(response, `test-${version.templateId}-v${version.versionNo}.docx`)
  },
  publish: (version: TemplateVersion): Promise<Response<TemplateVersion>> =>
    request.post(`${versionUrl(version.templateId, version.id)}/publish`, undefined, {
      ...longRequest,
      headers: ifMatch(version.etag),
    }),
  generate: (templateVersionId: string, input: JsonObject, key: string): Promise<Response<Generation>> =>
    request.post(generations, { templateVersionId, input }, {
      ...longRequest,
      headers: { 'Idempotency-Key': key },
    }),
  generations: (params: GenerationQuery): Promise<Response<Page<Generation>>> =>
    request.get(generations, { ...inlineErrors, params }),
  generation: (id: string, signal?: AbortSignal): Promise<Response<Generation>> =>
    request.get(`${generations}/${id}`, { ...inlineErrors, signal }),
  generationInput: (id: string, signal?: AbortSignal): Promise<Response<JsonObject>> =>
    request.get(`${generations}/${id}/input`, { ...inlineErrors, signal }),
  async generationFile(id: string, signal?: AbortSignal): Promise<DocxFile> {
    const response = await request.get<Blob, AxiosResponse<Blob>>(`${generations}/${id}/file`, { ...attachment, signal })
    return docx(response, `generation-${id}.docx`)
  },
}

export function downloadGeneratedDocument(row: DocumentGenerationFile, signal?: AbortSignal) {
  return row.type === 'TEMPLATE_VERSION'
    ? api.templateFile({ templateId: row.templateId, id: row.templateVersionId, versionNo: row.versionNo }, signal)
    : api.generationFile(row.generationId, signal)
}

export default api
