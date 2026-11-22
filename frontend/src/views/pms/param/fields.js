import { t } from '@/i18n'

export function getTypeOptions() {
  return [
    {
      label: t('文本'),
      value: 'text',
    },
    {
      label: t('数字'),
      value: 'number',
    },
    {
      label: t('开关'),
      value: 'boolean',
    },
    {
      label: t('单选'),
      value: 'select',
    },
  ]
}

export function fieldDefinitions(config = {}) {
  const fixed = Array.from({ length: 9 }, (_, i) => {
    const key = `value${i + 1}`
    return {
      label: t('值{0}', i + 1),
      type: 'text',
      showInTable: false,
      ...config?.fixedFields?.[key],
      key,
      storage: 'fixed',
    }
  })
  return [
    ...fixed,
    ...(config?.extraFields || []).map(field => ({ type: 'text', ...field, storage: 'extra' })),
  ]
}

export function fieldDraft(field, keyLocked = false) {
  return {
    required: false,
    showInTable: false,
    options: [],
    ...field,
    hasDefault: Object.hasOwn(field, 'defaultValue'),
    keyLocked,
  }
}

export function applyDefaults(config) {
  const item = {
    code: '',
    name: '',
    enable: true,
    sort: 0,
    remark: '',
    extra: {},
  }
  for (const field of fieldDefinitions(config)) {
    const target = field.storage === 'fixed'
      ? item
      : item.extra
    target[field.key] = field.defaultValue === undefined
      ? null
      : structuredClone(field.defaultValue)
  }
  return item
}

export function itemPayload(form) {
  const result = {
    code: form.code,
    name: form.name,
    nameEn: form.nameEn ?? null,
    enable: form.enable,
    sort: form.sort,
    remark: form.remark || null,
    extra: structuredClone(toPlain(form.extra || {})),
  }
  for (let i = 1; i <= 9; i++) {
    const value = form[`value${i}`]
    result[`value${i}`] = value === null || value === undefined || value === ''
      ? null
      : String(value)
  }
  return result
}

export function toPlain(value) {
  return JSON.parse(JSON.stringify(value))
}

export function validateConfig(config) {
  const keys = new Set()
  const fixed = config.fixedFields || {}
  for (const key of Object.keys(fixed)) {
    if (!/^value[1-9]$/.test(key))
      throw new Error(t('固定字段只允许值1～值9'))
  }
  if ((config.extraFields || []).length > 50)
    throw new Error(t('扩展字段不能超过50个'))
  for (const field of fieldDefinitions(config).filter(f => f.storage === 'extra' || fixed[f.key])) {
    if (!field.label?.trim() || field.label.length > 100)
      throw new Error(t('字段名称应为1～100字'))
    if (!getTypeOptions().some(option => option.value === field.type))
      throw new Error(t('请选择有效的字段类型'))
    if (field.storage === 'extra') {
      if (!/^[A-Z]\w{0,63}$/i.test(field.key) || ['constructor', 'prototype', '__proto__'].includes(field.key) || keys.has(field.key))
        throw new Error(t('扩展字段标识应以字母开头，仅含字母数字下划线，且不能重复'))
      keys.add(field.key)
    }
    if (field.type === 'select') {
      const values = new Set()
      if (!field.options?.length || field.options.length > 100)
        throw new Error(t('{0}应配置1～100个选项', field.label))
      for (const option of field.options) {
        if (!option.label?.trim() || !option.value?.trim() || values.has(option.value))
          throw new Error(t('{0}的选项名称和值不能为空，且值不能重复', field.label))
        values.add(option.value)
      }
    }
  }
  return config
}

/** A request can finish only if it still owns the current generation. */
export function latestRequest() {
  let generation = 0
  return {
    invalidate() { generation++ },
    async run(loader) {
      const own = ++generation
      try {
        const data = await loader()
        return own === generation
          ? { current: true, data }
          : { current: false }
      }
      catch (error) {
        return own === generation
          ? { current: true, error }
          : { current: false }
      }
    },
  }
}

export function displayValue(field, value) {
  if (value === undefined || value === null || value === '')
    return '—'
  if (field.type === 'select')
    return field.options?.find(option => option.value === String(value))?.label || String(value)
  if (field.type === 'boolean') {
    return value === true || value === 'true'
      ? t('是')
      : t('否')
  }
  return typeof value === 'object'
    ? JSON.stringify(value)
    : String(value)
}
