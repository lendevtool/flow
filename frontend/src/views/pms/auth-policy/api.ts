import { request } from '@/utils'

export type CharacterType = 'uppercase' | 'lowercase' | 'digit' | 'special'
export type PolicyValue = number | boolean | CharacterType[]
export type PolicyConfig = Record<string, Record<string, PolicyValue>>

export interface PolicySnapshot {
  code: string
  revision: number
  schemaVersion: number
  config: PolicyConfig
  defaults: PolicyConfig
  updatedAt: string
  updatedBy: number | null
}

export type MfaRotationState = 'IDLE' | 'RUNNING' | 'COMPLETED' | 'FAILED'

export interface MfaRotationStatus {
  activeKeyId: string | null
  targetKeyId: string | null
  activeSince: string | null
  lastCompletedAt: string | null
  nextRotationAt: string | null
  reminderAt: string | null
  reminderDue: boolean
  overdue: boolean
  status: MfaRotationState
  errorCode: string | null
  pendingBindings: number
  canStart: boolean
}

export type MfaKeyVersionState = 'DRAFT' | 'PUBLISHED'

export interface MfaKeyManagementSnapshot {
  revision: number
  configured: boolean
  targetKeyId: string | null
  lastPublishedAt: string | null
  versions: {
    id: string
    status: MfaKeyVersionState
    eligible: boolean
    createdAt: string | null
    publishedAt: string | null
  }[]
}

interface MfaKeysResponse {
  code: number
  data: MfaKeyManagementSnapshot
}

interface GeneratedMfaKeyResponse {
  code: number
  data: { key: string }
}

interface RotationResponse {
  code: number
  data: MfaRotationStatus
}

interface PolicyResponse {
  code: number
  data: PolicySnapshot
}

const url = '/system-policy/auth-security'

export default {
  get: () => request.get<unknown, PolicyResponse>(url),
  getMfaKeys: () => request.get<unknown, MfaKeysResponse>(`${url}/mfa-keys`, {
    headers: { 'X-Session-Passive': 'true' },
  }),
  generateMfaKey: () => request.post<unknown, GeneratedMfaKeyResponse>(`${url}/mfa-keys/generate`),
  stageMfaKey: (keyId: string, key: string) =>
    request.post<unknown, MfaKeysResponse>(`${url}/mfa-keys`, { keyId, key }),
  refreshMfaKeys: (revision: number, targetKeyId: string | null, activate: boolean) =>
    request.post<unknown, MfaKeysResponse>(`${url}/mfa-keys/refresh`, { revision, targetKeyId, activate }),
  getRotation: () => request.get<unknown, RotationResponse>(`${url}/mfa-rotation`, {
    headers: { 'X-Session-Passive': 'true' },
  }),
  startRotation: () => request.post<unknown, RotationResponse>(`${url}/mfa-rotation/start`),
  save: (revision: number, config: PolicyConfig) =>
    request.put<unknown, PolicyResponse>(url, { revision, config }),
  reset: (revision: number) => request.post<unknown, PolicyResponse>(`${url}/reset`, { revision }),
}
