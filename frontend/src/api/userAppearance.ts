import type { Language } from '@/i18n'
import { request } from '@/utils'

export interface AppearancePreferences {
  colorMode: 'auto' | 'light' | 'dark'
  primaryColor: string
  language: Language
  layout: 'simple' | 'normal' | 'full' | 'empty'
  tableSize: 'small' | 'medium' | 'large'
  layoutSettingVisible: boolean
}

export function updateUserAppearance(patch: Partial<AppearancePreferences>, signal: AbortSignal): Promise<{ code: number }> {
  return request.patch<unknown, { code: number }>('/user/profile/appearance', patch, { signal })
}
