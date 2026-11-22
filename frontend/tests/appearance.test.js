import { createPinia } from 'pinia'
import persistedState from 'pinia-plugin-persistedstate'
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'

function storage(initial = {}) {
  const entries = new Map(Object.entries(initial))
  return {
    getItem: key => entries.get(key) ?? null,
    setItem: (key, value) => entries.set(key, value),
    clear: () => entries.clear(),
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

it('migrates session appearance and restores local preferences without the original session', async () => {
  const localStorage = storage()
  vi.stubGlobal('window', { localStorage })
  const sessionStorage = storage({
    app: JSON.stringify({
      collapsed: true,
      layout: 'full',
      tableSize: 'large',
      primaryColor: '#225588',
    }),
  })
  vi.stubGlobal('sessionStorage', sessionStorage)
  const { useAppStore } = await import('@/store/modules/app')

  function openApp() {
    const pinia = createPinia().use(persistedState)
    createApp({}).use(pinia)
    return useAppStore(pinia)
  }

  const app = openApp()
  expect(app).toMatchObject({
    collapsed: true,
    layout: 'full',
    tableSize: 'large',
    primaryColor: '#225588',
    layoutSettingVisible: true,
  })

  sessionStorage.clear()
  const reopened = openApp()
  expect(reopened).toMatchObject({
    collapsed: false,
    layout: 'full',
    tableSize: 'large',
    primaryColor: '#225588',
  })
  reopened.setLayout('simple')
  reopened.tableSize = 'small'
  reopened.setPrimaryColor('#226644')
  reopened.layoutSettingVisible = false
  await nextTick()

  const restored = openApp()
  expect(restored).toMatchObject({
    layout: 'simple',
    tableSize: 'small',
    primaryColor: '#226644',
    layoutSettingVisible: false,
  })
  restored.layoutSettingVisible = true
  await nextTick()
  expect(openApp().layoutSettingVisible).toBe(true)
})
