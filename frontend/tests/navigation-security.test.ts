// @vitest-environment jsdom

import { expect, it } from 'vitest'
import { createApp, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { externalNavigationTarget, internalNavigationPath } from '@/utils/navigationTarget'
import FrameView from '@/views/iframe/index.vue'

it('validates full external URLs and keeps internal destinations origin-relative', () => {
  expect(externalNavigationTarget(' https://example.test/docs ')).toEqual({ kind: 'web', url: 'https://example.test/docs' })
  expect(externalNavigationTarget('tel:+123456789')).toEqual({ kind: 'contact', url: 'tel:+123456789' })
  expect(externalNavigationTarget('https://guest@example.test')).toBeUndefined()
  expect(externalNavigationTarget('data:text/plain,preview')).toBeUndefined()
  expect(externalNavigationTarget(`https://example.test/${String.fromCharCode(9)}docs`)).toBeUndefined()
  expect(internalNavigationPath('/workbench/todos?view=mine#open')).toBe('/workbench/todos?view=mine#open')
  expect(internalNavigationPath('//example.test/docs')).toBeUndefined()
  expect(internalNavigationPath('/\\example.test/docs')).toBeUndefined()
})

it('renders web targets in an opaque sandbox and omits frames for non-web targets', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/web',
        component: FrameView,
        meta: { originPath: 'https://example.test/docs' },
      },
      {
        path: '/contact',
        component: FrameView,
        meta: { originPath: 'mailto:team@example.test' },
      },
    ],
  })
  await router.push('/web')
  const container = document.createElement('div')
  document.body.append(container)
  const app = createApp(() => h(FrameView)).use(router)
  app.mount(container)
  try {
    const frame = container.querySelector('iframe')
    expect(frame?.getAttribute('src')).toBe('https://example.test/docs')
    expect(frame?.getAttribute('sandbox')).toBe('allow-forms allow-scripts')
    expect(frame?.getAttribute('referrerpolicy')).toBe('no-referrer')

    await router.push('/contact')
    await nextTick()
    expect(container.querySelector('iframe')).toBeNull()
  }
  finally {
    app.unmount()
    container.remove()
  }
})
