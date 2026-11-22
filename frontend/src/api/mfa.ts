import type { AxiosRequestConfig } from 'axios'
import { request } from '@/utils'

export interface MfaChallenge {
  ticket: string
  enrollment: boolean
  qrCode: string | null
  setupKey: string | null
  expiresInSeconds: number
}

export interface MfaLoginResult {
  accessToken: string
  passwordChangeRequired: boolean
  recoveryCodes: string[]
}

interface MfaStatus {
  configured: boolean
  globallyRequired: boolean
  personalEnabled: boolean
  bound: boolean
  recentRecoveryLogin: boolean
}

interface MfaEnrollment {
  recoveryCodes: string[]
}

interface ApiResponse<T> {
  code: number
  data: T
}

const base = '/auth/mfa'
const anonymous: AxiosRequestConfig & { needToken: false } = { needToken: false }

export default {
  verifyLogin: (ticket: string, code: string) =>
    request.post<unknown, ApiResponse<MfaLoginResult>>(`${base}/verify`, { ticket, code }, anonymous),
  status: () => request.get<unknown, ApiResponse<MfaStatus>>(base),
  setup: (currentCode?: string) => request.post<unknown, ApiResponse<MfaChallenge>>(`${base}/setup`, { currentCode }),
  confirm: (ticket: string, code: string) =>
    request.post<unknown, ApiResponse<MfaEnrollment>>(`${base}/setup/confirm`, { ticket, code }),
  preference: (enabled: boolean, code: string) =>
    request.put<unknown, ApiResponse<MfaStatus>>(`${base}/preference`, { enabled, code }),
}

export type { MfaStatus }
