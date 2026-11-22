import { readFileSync } from 'node:fs'
import { JSDOM } from 'jsdom'
import { afterEach, expect, it } from 'vitest'

const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8')
let dom

function loadStartupPage({ mode, appearance, legacyColor, systemDark = false, storageBlocked = false }) {
  dom = new JSDOM(html, {
    url: 'http://localhost:3086/pms/workflow',
    runScripts: 'dangerously',
    beforeParse(window) {
      window.matchMedia = () => ({ matches: systemDark })
      if (storageBlocked) {
        Object.defineProperty(window, 'localStorage', {
          get() { throw new window.DOMException('Storage blocked', 'SecurityError') },
        })
        return
      }
      if (mode)
        window.localStorage.setItem('vueuse-color-scheme', mode)
      if (appearance)
        window.localStorage.setItem('flow-appearance', appearance)
      if (legacyColor)
        window.sessionStorage.setItem('app', JSON.stringify({ primaryColor: legacyColor }))
    },
  })
  return dom.window.document.documentElement
}

afterEach(() => dom?.window.close())

it('uses the saved light primary on refresh even when the system is dark', () => {
  const root = loadStartupPage({
    mode: 'light',
    appearance: JSON.stringify({ primaryColor: '#165DFF' }),
    legacyColor: '#225588',
    systemDark: true,
  })

  expect(root.classList.contains('dark')).toBe(false)
  expect(root.style.getPropertyValue('--flow-loading-color')).toBe('#165DFF')
})

it('uses the resolved dark primary when automatic mode follows a dark system', () => {
  const root = loadStartupPage({
    mode: 'auto',
    appearance: JSON.stringify({
      primaryColor: '#165DFF',
      loadingPalette: { source: '#165DFF', light: '#165DFF', dark: '#3C7EFF' },
    }),
    systemDark: true,
  })

  expect(root.classList.contains('dark')).toBe(true)
  expect(root.style.getPropertyValue('--flow-loading-color')).toBe('#3C7EFF')
})

it('recovers the legacy primary when the current cache is malformed', () => {
  const root = loadStartupPage({ mode: 'dark', appearance: '{', legacyColor: '#225588' })

  expect(root.classList.contains('dark')).toBe(true)
  expect(root.style.getPropertyValue('--flow-loading-color')).toBe('#225588')
})

it('follows the system when browser storage is unavailable', () => {
  const root = loadStartupPage({ systemDark: true, storageBlocked: true })

  expect(root.classList.contains('dark')).toBe(true)
  expect(root.style.getPropertyValue('--flow-loading-color')).toBe('')
})
