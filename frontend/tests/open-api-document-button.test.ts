// @vitest-environment jsdom

import type { App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { setLanguage } from '@/i18n'
import DocsDownloadButton from '@/views/pms/open-api/DocsDownloadButton.vue'

let app: App | undefined
let container: HTMLDivElement | undefined

afterEach(() => {
  app?.unmount()
  container?.remove()
  app = undefined
  container = undefined
  setLanguage('zh-CN')
})

function mount(loading = false) {
  setLanguage('zh-CN')
  const download = vi.fn()
  container = document.createElement('div')
  document.body.append(container)
  app = createApp({
    render: () => h(DocsDownloadButton, { loading, onDownload: download }),
  })
  app.mount(container)
  return download
}

it.each([
  ['JSON (.json)', 'json'],
  ['Markdown (.md)', 'markdown'],
])('offers %s and emits its format', async (label, format) => {
  const download = mount()
  container?.querySelector('button')?.click()
  await nextTick()
  await vi.waitFor(() => {
    const option = [
      ...document.querySelectorAll('.n-dropdown-option-body'),
    ].find(item => item.textContent === label)
    expect(option).toBeDefined()
    option?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(download).toHaveBeenCalledWith(format)
  })
})

it('disables the download button while a file is being prepared', async () => {
  const download = mount(true)
  const button = container?.querySelector('button')
  expect(button?.disabled).toBe(true)
  button?.click()
  await nextTick()
  expect(download).not.toHaveBeenCalled()
})
