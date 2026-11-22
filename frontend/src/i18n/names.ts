import { language } from './index'

export function displayName(name: unknown, nameEn?: unknown): string {
  if (language.value === 'en-US' && typeof nameEn === 'string' && nameEn.trim())
    return nameEn.trim()
  return typeof name === 'string'
    ? name
    : ''
}

export function userDisplayName(user?: { username?: string, nickName?: string, nameEn?: string } | null): string {
  return displayName(user?.nickName?.trim() || user?.username, user?.nameEn)
}
