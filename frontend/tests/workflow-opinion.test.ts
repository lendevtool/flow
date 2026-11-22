// @vitest-environment jsdom

import { expect, it } from 'vitest'
import { createApp, h, nextTick, ref } from 'vue'
import { renderOpinion } from '@/views/workbench/workflow/markdown'
import WorkflowOpinion from '@/views/workbench/workflow/WorkflowOpinion.vue'

it('renders approval input that resembles markup as text', () => {
  const container = document.createElement('div')
  const text = '<span data-kind="note">记录</span>'
  container.innerHTML = renderOpinion(`**${text}**`)
  expect(container.querySelector('strong')?.textContent).toBe(text)
  expect(container.querySelector('span')).toBeNull()
})

it('keeps the approval limit and formatting and preview shortcuts in the workflow editor', async () => {
  const value = ref('同意')
  const container = document.createElement('div')
  document.body.append(container)
  const app = createApp(() => h(WorkflowOpinion, {
    'modelValue': value.value,
    'onUpdate:modelValue': text => value.value = text,
  }))
  app.mount(container)
  try {
    const editor = container.querySelector('textarea')!
    expect(editor.maxLength).toBe(500)
    expect(editor.getAttribute('aria-label')).toBe('审批意见')
    editor.setSelectionRange(0, 2)
    editor.dispatchEvent(new KeyboardEvent('keydown', { code: 'KeyB', ctrlKey: true, bubbles: true }))
    await nextTick()
    expect(value.value).toBe('**同意**')

    editor.dispatchEvent(new KeyboardEvent('keydown', {
      code: 'KeyM',
      ctrlKey: true,
      shiftKey: true,
      bubbles: true,
    }))
    await nextTick()
    expect(container.querySelector('.preview strong')?.textContent).toBe('同意')
    expect(document.activeElement).toBe(container.querySelector('.preview'))
  }
  finally {
    app.unmount()
    container.remove()
  }
})
