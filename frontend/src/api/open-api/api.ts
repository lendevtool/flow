import type { AxiosResponse } from 'axios'
import { request } from '@/utils'

interface Response<T> {
  data: T
}

export interface PageQuery {
  pageNo: number
  pageSize: number
}

interface Page<T> {
  pageData: T[]
  total: number
}

export type EndpointStatus = 'REGISTERED' | 'PUBLISHED' | 'OFFLINE'
export type GrantStatus = 'GRANTED' | 'REVOKED'
export type SecretStatus = 'CANDIDATE' | 'ACTIVE' | 'RETIRING' | 'REVOKED'
export type RevokeMode = 'RETIRE' | 'ABANDON' | 'COMPROMISED'
export type CallResult = 'SUCCESS' | 'DENIED' | 'FAILED'

export interface App {
  id: number
  clientId: string
  name: string
  environment: string
  enabled: boolean
  ownerUserId: number | null
  ownerDisplayName: string | null
  description: string | null
  rateLimitCount: number
  rateLimitWindowSeconds: number
  version: number
  createdAt: string
  updatedAt: string
}

export type AppWrite = Pick<App, 'name' | 'ownerUserId' | 'description' | 'rateLimitCount' | 'rateLimitWindowSeconds'>

export type AppPatch = Partial<AppWrite> & Pick<App, 'version'>

export interface AppQuery extends PageQuery {
  keyword?: string
  environment?: string
  enabled?: boolean
}

interface SourceValue {
  name: string
  type: string
  format?: string | null
}

export interface SourceParameter extends SourceValue {
  required: boolean
}

export interface SourceField extends SourceValue {
  nullable: boolean
}

interface SourceContract {
  sourcePath: string
  parameters: SourceParameter[]
  fields: SourceField[]
  collection: boolean
  page?: boolean
  wholeResponse?: boolean
}

export interface EndpointSource extends SourceContract {
  httpMethod: string
  moduleCode: string
  importable: boolean
  reason: 'READ_ONLY' | 'POLICY_REQUIRED' | null
}

interface EndpointSourceDetails extends SourceContract {
  contractMatches: boolean
}

export interface EndpointImport {
  sourcePath: string
  displayName: string
  endpointCode: string
  pathTemplate: string
  responseFields?: string[]
}

export interface Endpoint {
  id: number
  endpointCode: string
  moduleCode: string
  displayName: string
  httpMethod: string
  pathTemplate: string
  apiVersion: string
  status: EndpointStatus
  version: number
  documentUrl: string
  authorizedAppCount: number
  source: EndpointSourceDetails | null
}

export interface EndpointQuery extends PageQuery {
  keyword?: string
  moduleCode?: string
  status?: EndpointStatus
}

export interface Grant {
  appId: number
  endpointId: number
  endpointCode: string
  displayName: string
  endpointStatus: EndpointStatus
  status: GrantStatus
  rateLimitCount: number | null
  rateLimitWindowSeconds: number | null
  version: number
}

export type GrantTarget = Pick<Grant, 'endpointId' | 'endpointCode' | 'displayName' | 'endpointStatus'>

export type GrantWrite = {
  status: 'GRANTED'
  rateLimitCount: number | null
  rateLimitWindowSeconds: number | null
  version: number | null
} | {
  status: 'REVOKED'
  version: number
}

export interface OwnerOption {
  id: number
  displayName: string
}

export interface SecretVersion {
  versionNo: number
  status: SecretStatus
  expiresAt: string
  activatedAt: string | null
  revokedAt: string | null
  tokensRevokedAt: string | null
  createdAt: string
}

export interface IssuedSecret {
  versionNo: number
  clientSecret: string
  expiresAt: string
}

export interface CallRecord {
  id: number
  requestId: string
  occurredAt: string
  durationMs: number
  httpStatus: number
  endpointId: number | null
  endpointCode: string | null
  errorCode: string | null
  sourceIp: string | null
  result: CallResult
}

export interface CallQuery extends PageQuery {
  from?: string
  to?: string
  endpointId?: number
  requestId?: string
  result?: CallResult
}

const base = '/admin/open-api'
const appPath = (id: number) => `${base}/apps/${id}`
const endpointPath = (id: number) => `${base}/endpoints/${id}`
const docsDownload = { responseType: 'blob' as const, keepAttachmentResponse: true }
const secretWrite = { needTip: false, headers: {} }
const secretPath = (id: number, version: number) => `${appPath(id)}/secrets/${version}`

// MeCrud 会记录请求失败，重新抛出无 Axios 请求配置的错误。
async function page<T>(url: string, params: PageQuery, signal?: AbortSignal): Promise<Response<Page<T>>> {
  try {
    return await request.get(url, { params, signal })
  }
  catch {
    throw new Error('Open API page request failed')
  }
}

export default {
  apps: (params: AppQuery) => page<App>(`${base}/apps`, params),
  app: (id: number, signal?: AbortSignal): Promise<Response<App>> =>
    request.get(appPath(id), { signal }),
  createApp: (payload: AppWrite): Promise<Response<App>> =>
    request.post(`${base}/apps`, payload),
  updateApp: (id: number, payload: AppPatch): Promise<Response<App>> =>
    request.patch(appPath(id), payload),
  appStatus: (id: number, enabled: boolean, version: number): Promise<Response<App>> =>
    request.put(`${appPath(id)}/status`, { enabled, version }),
  owners: (params: PageQuery & { keyword?: string }, signal: AbortSignal) =>
    page<OwnerOption>(`${base}/owner-options`, params, signal),
  endpoints: (params: EndpointQuery, signal?: AbortSignal) =>
    page<Endpoint>(`${base}/endpoints`, params, signal),
  endpointSources: (params: { importable?: boolean }, signal?: AbortSignal): Promise<Response<EndpointSource[]>> =>
    request.get(`${base}/endpoint-sources`, { params, signal }),
  importEndpoint: (payload: EndpointImport): Promise<Response<Endpoint>> =>
    request.post(`${base}/endpoints/import`, payload),
  endpoint: (id: number, signal?: AbortSignal): Promise<Response<Endpoint>> =>
    request.get(endpointPath(id), { signal }),
  endpointApps: (id: number, params: PageQuery) =>
    page<App>(`${endpointPath(id)}/apps`, params),
  endpointStatus: (id: number, status: 'PUBLISHED' | 'OFFLINE', version: number): Promise<Response<Endpoint>> =>
    request.put(`${endpointPath(id)}/status`, { status, version }),
  confirmEndpointSource: (id: number, version: number): Promise<Response<Endpoint>> =>
    request.put(`${endpointPath(id)}/source-contract`, { version }),
  docs: (): Promise<AxiosResponse<Blob>> => request.get(`${base}/docs/v1`, docsDownload),
  grants: (id: number, signal?: AbortSignal): Promise<Response<Grant[]>> =>
    request.get(`${appPath(id)}/grants`, { signal }),
  saveGrant: (id: number, endpointId: number, payload: GrantWrite): Promise<Response<Grant>> =>
    request.put(`${appPath(id)}/grants/${endpointId}`, payload),
  secrets: (id: number, signal?: AbortSignal): Promise<Response<SecretVersion[]>> =>
    request.get(`${appPath(id)}/secrets`, { signal }),
  createSecret: (id: number): Promise<Response<IssuedSecret>> =>
    request.post(`${appPath(id)}/secrets`, undefined, secretWrite),
  activateSecret: (id: number, version: number): Promise<Response<{ activated: SecretVersion, replaced: SecretVersion | null }>> =>
    request.post(`${secretPath(id, version)}/activate`),
  revokeSecret: (id: number, version: number, mode: RevokeMode): Promise<Response<SecretVersion>> =>
    request.post(`${secretPath(id, version)}/revoke`, { mode }),
  calls: (id: number, params: CallQuery) => page<CallRecord>(`${appPath(id)}/calls`, params),
}
