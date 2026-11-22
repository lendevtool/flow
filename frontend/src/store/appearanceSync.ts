import type { AppearancePreferences } from '@/api/userAppearance'
import { watch } from 'vue'
import { updateUserAppearance } from '@/api/userAppearance'
import { language, setLanguage } from '@/i18n'
import { defaultLayout, defaultPrimaryColor } from '@/settings'
import { useAppStore, useUserStore } from './modules'

interface AppearanceSession {
  controller: AbortController
  saved: AppearancePreferences
  saving: boolean
}

type AppearanceKey = keyof AppearancePreferences

function applyAppearance(app: ReturnType<typeof useAppStore>, patch: Partial<AppearancePreferences>): void {
  const { language, ...appearance } = patch
  app.$patch((state) => {
    Object.assign(state, appearance)
  })
  setLanguage(language)
}

/** 登录时以账号偏好为准，旧版浏览器缓存只用于登录前显示。 */
export function restoreAccountAppearance(appearance?: AppearancePreferences | null): void {
  applyAppearance(useAppStore(), appearance ?? {
    colorMode: 'auto',
    primaryColor: defaultPrimaryColor,
    language: 'zh-CN',
    layout: defaultLayout,
    tableSize: 'medium',
    layoutSettingVisible: true,
  })
}

function changedFields(saved: AppearancePreferences, next: AppearancePreferences): AppearanceKey[] {
  return (Object.keys(next) as AppearanceKey[]).filter(key => next[key] !== saved[key])
}

/** 登录期间同步实际修改的字段，切换账号时取消旧会话的请求。 */
export function setupAppearanceSync(): void {
  const app = useAppStore()
  const user = useUserStore()
  let session: AppearanceSession | undefined
  let timer: ReturnType<typeof setTimeout> | undefined

  function currentAppearance(): AppearancePreferences {
    return {
      colorMode: app.colorMode,
      primaryColor: app.primaryColor,
      language: language.value,
      layout: app.layout,
      tableSize: app.tableSize,
      layoutSettingVisible: app.layoutSettingVisible,
    }
  }

  async function save(): Promise<void> {
    const active = session
    if (!active || active.saving)
      return

    const requested = currentAppearance()
    const changes = changedFields(active.saved, requested)
    if (!changes.length)
      return

    const patch = Object.fromEntries(changes.map(key => [key, requested[key]]))
    active.saving = true
    try {
      await updateUserAppearance(patch, active.controller.signal)
      Object.assign(active.saved, patch)
    }
    catch {
      if (session === active) {
        // 请求过程中若用户再次修改该字段，保留最新值交给下一次保存。
        const latest = currentAppearance()
        applyAppearance(app, Object.fromEntries(changes
          .filter(key => latest[key] === requested[key])
          .map(key => [key, active.saved[key]])))
      }
    }
    finally {
      active.saving = false
      if (session === active)
        scheduleSave()
    }
  }

  function scheduleSave(): void {
    clearTimeout(timer)
    if (!session || session.saving)
      return

    const changes = changedFields(session.saved, currentAppearance())
    if (changes.length === 1 && changes[0] === 'primaryColor') {
      // 拖动取色器会连续更新预览，等待用户停止拖动再保存颜色。
      timer = setTimeout(() => void save(), 200)
    }
    else if (changes.length) {
      void save()
    }
  }

  watch(() => user.userId, (id) => {
    clearTimeout(timer)
    session?.controller.abort()
    // 路由守卫先恢复服务端偏好，再设置当前用户。
    session = id == null
      ? undefined
      : {
          controller: new AbortController(),
          saved: currentAppearance(),
          saving: false,
        }
  }, { flush: 'sync' })
  watch(currentAppearance, scheduleSave)
}
