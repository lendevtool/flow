import type { ValidationResult } from './api'
import type { FieldForm } from './model'
import { language, t } from '@/i18n'
import { selectedCharacters } from './model'

const characterLabels: Record<string, string> = {
  CHINESE: '汉字',
  ENGLISH: '英文字母（A-Z、a-z）',
  DIGITS: '数字（0-9）',
  SPACE: '普通空格',
  SEPARATORS: '连接符（-、_）',
  CHINESE_PUNCTUATION: '中文标点',
  ENGLISH_PUNCTUATION: '英文标点',
}

const invisibleCharacterNames: Partial<Record<number, string>> = {
  0x20: '空格',
  0x09: '制表符',
  0x0A: '换行符',
  0x0D: '回车符',
  0xA0: '不换行空格',
  0x3000: '全角空格',
}

/** 仅补充旧版字符错误，其他结果保留服务端的提示。 */
export function fieldValidationMessage(config: FieldForm, value: unknown, failure: Pick<Extract<ValidationResult, { valid: false }>, 'code' | 'message'>): string {
  const { code, message } = failure
  if (code !== 'error.field.validation.text.characters' || typeof value !== 'string' || !config.textCharLimitEnabled)
    return message

  const match = /^(?:第 ([\d,]+) 个字符不属于此字段允许的字符类别|Character ([\d,]+) is outside the allowed character classes)$/.exec(message)
  if (!match)
    return message

  const position = Number((match[1] ?? match[2]).replaceAll(',', ''))
  const character = Array.from(value)[position - 1]
  const codePoint = character?.codePointAt(0)
  const allowed = selectedCharacters(config).map(key => t(characterLabels[key]))
  if (codePoint === undefined || !allowed.length)
    return message

  const separator = language.value === 'en-US'
    ? ', '
    : '、'
  const labels = allowed.join(separator)
  const name = invisibleCharacterNames[codePoint]
  if (name)
    return t('第 {0} 个字符是{1}，不符合限制。此字段只允许{2}。', position, t(name), labels)

  const shown = (codePoint >= 0x21 && codePoint <= 0x7E) || /^[\p{L}\p{Nd}]$/u.test(character)
    ? character
    : `U+${codePoint.toString(16).toUpperCase().padStart(4, '0')}`
  return t('第 {0} 个字符「{1}」不符合限制。此字段只允许{2}。', position, shown, labels)
}
