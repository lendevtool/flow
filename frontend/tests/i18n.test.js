import { afterEach, expect, it, vi } from 'vitest'
import { language, naiveDateLocale, naiveLocale, setLanguage, t } from '../src/i18n'
import { displayName, userDisplayName } from '../src/i18n/names'
import { navigationLabel } from '../src/i18n/navigation'

afterEach(() => {
  setLanguage('zh-CN')
  vi.unstubAllGlobals()
})

it('switches application and component messages together and persists the selected locale', async () => {
  const preferences = new Map()
  vi.stubGlobal('localStorage', {
    getItem: key => preferences.get(key) ?? null,
    setItem: (key, value) => preferences.set(key, value),
  })

  expect(t('请输入用户名')).toBe('请输入用户名')
  setLanguage('en-US')
  expect(naiveLocale.value.name).toBe('en-US')
  expect(naiveDateLocale.value.name).toBe('en-US')
  expect(t('请输入用户名')).toBe('Enter your username')
  expect(t('确定删除「{0}」吗？{1}', '北京', '')).toBe('Delete “北京”?')
  expect(navigationLabel('ParamMgt', '参数管理')).toBe('Parameters')
  expect(navigationLabel('ParamMgt', '公司配置')).toBe('公司配置')
  expect(navigationLabel('CustomMenu', '参数管理')).toBe('参数管理')

  setLanguage('fr-FR')
  expect(language.value).toBe('en-US')
  vi.resetModules()
  const restarted = await import('../src/i18n')
  expect(restarted.language.value).toBe('en-US')
  setLanguage('zh-CN')
  expect(naiveLocale.value.name).toBe('zh-CN')
})

it('keeps the preference for the current visit when storage is unavailable', () => {
  vi.stubGlobal('localStorage', {
    setItem() { throw new Error('Storage unavailable') },
  })

  setLanguage('en-US')
  expect(t('取消')).toBe('Cancel')
})

it('uses English overrides only in English and keeps original names as fallbacks', () => {
  const user = { username: 'login-id', nickName: '小张', nameEn: 'Jane Smith' }

  expect(displayName('待处理', 'Pending')).toBe('待处理')
  expect(userDisplayName(user)).toBe('小张')
  expect(navigationLabel('ParamMgt', '参数管理', 'Company parameters')).toBe('参数管理')

  setLanguage('en-US')
  expect(displayName('待处理', ' Pending ')).toBe('Pending')
  expect(displayName('已完成', '  ')).toBe('已完成')
  expect(userDisplayName(user)).toBe('Jane Smith')
  expect(userDisplayName({ ...user, nameEn: null })).toBe('小张')
  expect(userDisplayName({ username: 'login-id' })).toBe('login-id')
  expect(navigationLabel('ParamMgt', '参数管理', 'Company parameters')).toBe('Company parameters')
  expect(navigationLabel('ParamMgt', '参数管理', null)).toBe('Parameters')
})
