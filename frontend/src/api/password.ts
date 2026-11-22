import { t } from '@/i18n'
import { request } from '@/utils'
import { resolveResError } from '@/utils/http/helpers'

export type PasswordCharacterType = 'uppercase' | 'lowercase' | 'digit' | 'special'

export interface PasswordRules {
  minLength: number
  maxLength: number
  requiredCharacterTypes: PasswordCharacterType[]
  rejectUsername: boolean
  rejectCurrent: boolean
}

interface PasswordChange {
  oldPassword: string
  newPassword: string
}

const inlineErrors = { needTip: false, headers: {} }

export const passwordApi = {
  rules: () => request.get<unknown, { data: PasswordRules }>('/auth/password/rules', inlineErrors),
  change: (data: PasswordChange) => request.post('/auth/password', data, inlineErrors),
}

export function passwordChangeError(error: unknown): string {
  if (typeof error === 'object' && error !== null) {
    if ('code' in error && error.code === 401) {
      resolveResError(401)
      return t('登录已过期，请重新登录')
    }
    if ('message' in error && typeof error.message === 'string' && error.message)
      return error.message
  }
  return t('密码修改失败，请重试')
}
