import { describe, expect, it } from 'vitest'
import { changeFieldType, newField, pickerDateTime, selectedCharacters, setCharacters, validationValue } from '../src/views/pms/field/model'
import { fieldValidationMessage } from '../src/views/pms/field/validationMessage'

describe('field definition form', () => {
  it('keeps common settings and clears hidden rules and defaults when the type changes', () => {
    const text = {
      ...newField('TEXT'),
      code: 'contract_number',
      name: '合同编号',
      nameEn: 'Contract Number',
      required: true,
      textCharLimitEnabled: true,
      textAllowDigits: true,
      textMaxLength: 10,
      defaultValue: '0001',
    }
    const numeric = changeFieldType(text, 'DECIMAL')
    expect(numeric).toMatchObject({
      code: 'contract_number',
      name: '合同编号',
      nameEn: 'Contract Number',
      required: true,
      dataType: 'DECIMAL',
      textCharLimitEnabled: false,
      textAllowDigits: false,
      textMaxLength: null,
      defaultValue: null,
    })
    const multi = {
      ...newField('ENUM_MULTI'),
      paramGroupId: 20,
      enumMinItems: 1,
      defaultValue: ['X'],
    }
    expect(changeFieldType(multi, 'ENUM')).toMatchObject({ paramGroupId: null, enumMinItems: null, defaultValue: null })
    expect(changeFieldType(text, 'TEXT')).toBe(text)
  })

  it('supports both one and several character categories', () => {
    const form = newField('TEXT')
    setCharacters(form, ['CHINESE', 'DIGITS'])
    expect(selectedCharacters(form)).toEqual(['CHINESE', 'DIGITS'])
    setCharacters(form, ['ENGLISH'])
    expect(selectedCharacters(form)).toEqual(['ENGLISH'])
    setCharacters(form, [])
    expect(selectedCharacters(form)).toEqual([])
  })

  it('preserves milliseconds when adapting ISO date times to the picker', () => {
    expect(pickerDateTime('2026-11-08T10:30')).toBe('2026-11-08T10:30:00.000')
    expect(pickerDateTime('2026-11-08T10:30:12.1')).toBe('2026-11-08T10:30:12.100')
    expect(pickerDateTime(null)).toBeNull()
  })
})

describe('field validation input', () => {
  it('explains a legacy character error using the Unicode position and saved character classes', () => {
    const config = {
      ...newField('TEXT'),
      textCharLimitEnabled: true,
      textAllowChinese: true,
    }
    const code = 'error.field.validation.text.characters'
    expect(fieldValidationMessage(config, 'aa', { code, message: '第 1 个字符不属于此字段允许的字符类别' }))
      .toBe('第 1 个字符「a」不符合限制。此字段只允许汉字。')
    expect(fieldValidationMessage(config, '\u{20BB7}\n', { code, message: 'Character 2 is outside the allowed character classes' }))
      .toBe('第 2 个字符是换行符，不符合限制。此字段只允许汉字。')
    const message = '第 1 个字符「a」属于英文字母（A-Z、a-z）。此字段只允许汉字。'
    expect(fieldValidationMessage(config, 'aa', { code, message })).toBe(message)
  })

  it.each([
    [' ', '空格'],
    ['\t', '制表符'],
    ['\u00A0', '不换行空格'],
    ['\u3000', '全角空格'],
  ])('names rejected whitespace in a legacy response (%j)', (character, label) => {
    const config = {
      ...newField('TEXT'),
      textCharLimitEnabled: true,
      textAllowEnglish: true,
      textAllowDigits: true,
    }
    const failure = {
      code: 'error.field.validation.text.characters',
      message: '第 4 个字符不属于此字段允许的字符类别',
    }
    expect(fieldValidationMessage(config, `122${character}aaa`, failure))
      .toBe(`第 4 个字符是${label}，不符合限制。此字段只允许英文字母（A-Z、a-z）、数字（0-9）。`)
  })

  it('preserves raw strings, numeric precision, and the distinction between empty text and null', () => {
    expect(validationValue('TEXT', '  \n')).toBe('  \n')
    expect(validationValue('TEXT', '')).toBe('')
    expect(validationValue('INTEGER', '9223372036854775807')).toBe('9223372036854775807')
    expect(validationValue('DECIMAL', '1.230')).toBe('1.230')
    expect(validationValue('TEXT', null)).toBeNull()
  })

  it('sends a boolean and preserves repeated codes and invalid item types for server validation', () => {
    expect(validationValue('BOOLEAN', 'false')).toBe(false)
    expect(validationValue('BOOLEAN', 'true')).toBe(true)
    expect(validationValue('ENUM_MULTI', '["A","A",1]')).toEqual(['A', 'A', 1])
    expect(validationValue('ENUM_MULTI', '[]')).toEqual([])
    expect(() => validationValue('ENUM_MULTI', '["A"')).toThrow(SyntaxError)
    expect(() => validationValue('ENUM_MULTI', '"A"')).toThrow(TypeError)
  })
})
