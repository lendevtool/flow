import { dateEnUS, dateZhCN, enUS, zhCN } from 'naive-ui'
import { computed, readonly, ref } from 'vue'
import en from './en.json'

export type Language = 'zh-CN' | 'en-US'

const storageKey = 'flow-language'
const fallbackLanguage: Language = 'zh-CN'
const english: Readonly<Partial<Record<string, string>>> = en

function initialLanguage(): Language {
  try {
    return globalThis.localStorage?.getItem(storageKey) === 'en-US'
      ? 'en-US'
      : fallbackLanguage
  }
  catch {
    return fallbackLanguage
  }
}

const currentLanguage = ref(initialLanguage())

export const language = readonly(currentLanguage)
export const naiveLocale = computed(() => language.value === 'en-US'
  ? enUS
  : zhCN)
export const naiveDateLocale = computed(() => language.value === 'en-US'
  ? dateEnUS
  : dateZhCN)

export function setLanguage(value: unknown): void {
  if (value !== 'zh-CN' && value !== 'en-US')
    return
  currentLanguage.value = value
  try {
    globalThis.localStorage?.setItem(storageKey, value)
  }
  catch {
    // 存储被禁用时，本次访问仍可切换语言。
  }
}

export function t(message: string, ...values: unknown[]): string {
  const translated = language.value === 'en-US' && Object.hasOwn(english, message)
    ? english[message] ?? message
    : message
  return values.length
    ? translated.replace(/\{(\d+)\}/g, (token, index) => index < values.length
        ? String(values[index])
        : token)
    : translated
}
