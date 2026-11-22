// @vitest-environment jsdom

import { createPinia, disposePinia, setActivePinia } from 'pinia'
import { expect, it, vi } from 'vitest'
import { effectScope, nextTick } from 'vue'
import { updateUserAppearance } from '@/api/userAppearance'
import { useAppStore, useUserStore } from '@/store'
import { restoreAccountAppearance, setupAppearanceSync } from '@/store/appearanceSync'

vi.mock('@/api/userAppearance', () => ({
  updateUserAppearance: vi.fn(async () => ({ code: 200 })),
}))

it('keeps the selected account theme when another tab writes an older theme to the browser cache', async () => {
  const pinia = createPinia()
  const sync = effectScope()
  setActivePinia(pinia)

  try {
    const app = useAppStore()
    sync.run(setupAppearanceSync)
    restoreAccountAppearance()
    app.colorMode = 'light'
    useUserStore().setUser({ id: 1 })
    await nextTick()

    app.toggleDark()
    await nextTick()
    expect(updateUserAppearance).toHaveBeenCalledExactlyOnceWith({ colorMode: 'dark' }, expect.any(AbortSignal))

    const key = 'vueuse-color-scheme'
    localStorage.setItem(key, 'light')
    window.dispatchEvent(new StorageEvent('storage', {
      key,
      oldValue: 'dark',
      newValue: 'light',
      storageArea: localStorage,
    }))
    await nextTick()

    expect(app.colorMode).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(updateUserAppearance).toHaveBeenCalledTimes(1)
  }
  finally {
    sync.stop()
    disposePinia(pinia)
    localStorage.clear()
    document.documentElement.classList.remove('dark', 'light')
  }
})
