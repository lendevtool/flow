// @vitest-environment jsdom

import type { App, VNode } from 'vue'
import { NButton, NDataTable, NPopconfirm } from 'naive-ui'
import { enUS, ProConfigProvider, zhCN } from 'pro-naive-ui'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref } from 'vue'
import OrderEditor from '@/components/common/OrderEditor.vue'
import TableActions from '@/components/common/TableActions.vue'
import { language, naiveDateLocale, setLanguage } from '@/i18n'

let app: App | undefined
let container: HTMLDivElement

beforeEach(() => {
  setLanguage('en-US')
  container = document.createElement('div')
  document.body.append(container)
})

afterEach(() => {
  app?.unmount()
  app = undefined
  container.remove()
  localStorage.removeItem('flow-language')
})

function mount(render: () => VNode) {
  app = createApp({ render })
  app.mount(container)
}

function button(name: string, root: HTMLElement = document.body) {
  const element = Array.from(root.querySelectorAll('button')).find((button) => {
    return (button.getAttribute('aria-label') ?? button.textContent?.trim()) === name
  })
  if (!element)
    throw new Error(`Button "${name}" was not rendered`)
  return element
}

function mountOrderEditor(saving = false) {
  const show = ref(true)
  const rows = ref([
    { id: 1, label: 'First document', detail: '' },
    { id: 2, label: 'Second document', detail: '' },
  ])
  const save = vi.fn(() => rows.value.map(row => row.id))

  mount(() => h(OrderEditor, {
    'title': 'Document order',
    'description': 'Arrange the documents',
    saving,
    'show': show.value,
    'rows': rows.value,
    'onUpdate:show': value => show.value = value,
    'onUpdate:rows': value => rows.value = value,
    'onSave': save,
  }))

  return { show, rows, save }
}

describe('vue component compatibility', () => {
  it('renders OrderEditor in a teleported modal and updates its model before saving', async () => {
    const { rows, save, show } = mountOrderEditor()

    expect(container.textContent).not.toContain('Document order')
    expect(document.body.textContent).toContain('Document order')
    button('Move down').click()
    await nextTick()

    expect(rows.value.map(row => row.id)).toEqual([2, 1])
    expect(Array.from(document.querySelectorAll('.sort-row [title]'), row => row.textContent?.trim()))
      .toEqual(['Second document', 'First document'])
    button('Save order').click()
    expect(save).toHaveBeenCalledOnce()
    expect(save).toHaveReturnedWith([2, 1])

    button('Cancel').click()
    expect(show.value).toBe(false)
  })

  it('disables reordering and cancellation while OrderEditor is saving', () => {
    const { rows, show } = mountOrderEditor(true)
    const moveDown = button('Move down')
    const cancel = button('Cancel')

    expect(moveDown.disabled).toBe(true)
    expect(cancel.disabled).toBe(true)
    moveDown.click()
    cancel.click()

    expect(rows.value.map(row => row.id)).toEqual([1, 2])
    expect(show.value).toBe(true)
  })

  it('renders TableActions in a Naive UI table cell and keeps button clicks out of the row handler', () => {
    const openDocument = vi.fn()
    const deleteDocument = vi.fn()
    const selectRow = vi.fn()
    mount(() => h(NDataTable, {
      data: [
        { id: 1, name: 'Release notes' },
      ],
      columns: [
        { key: 'name', title: 'Document' },
        {
          key: 'actions',
          title: 'Actions',
          render: () => h(TableActions, {
            iconOnly: true,
            actions: [
              {
                key: 'open',
                label: 'Open document',
                icon: 'i-fe:folder',
                onClick: openDocument,
              },
              {
                key: 'delete',
                label: 'Delete document',
                icon: 'i-fe:trash',
                disabled: true,
                onClick: deleteDocument,
              },
            ],
          }),
        },
      ],
      rowProps: () => ({ onClick: selectRow }),
    }))

    const row = container.querySelector('tbody')?.rows[0]
    if (!row)
      throw new Error('The document row was not rendered')
    expect(row.textContent).toContain('Release notes')
    button('Open document', row).click()
    expect(openDocument).toHaveBeenCalledOnce()
    expect(selectRow).not.toHaveBeenCalled()

    const deleteButton = button('Delete document', row)
    expect(deleteButton.disabled).toBe(true)
    deleteButton.click()
    expect(deleteDocument).not.toHaveBeenCalled()
    row.click()
    expect(selectRow).toHaveBeenCalledOnce()
  })

  it('updates Naive UI confirmation labels when the Pro provider locale changes', async () => {
    const confirm = vi.fn()
    mount(() => h(ProConfigProvider, {
      locale: language.value === 'en-US'
        ? enUS
        : zhCN,
      dateLocale: naiveDateLocale.value,
    }, {
      default: () => h(NPopconfirm, { show: true, onPositiveClick: confirm }, {
        trigger: () => h(NButton, null, () => 'Publish'),
        default: () => 'Publish this document?',
      }),
    }))

    expect(button('Confirm')).toBeInstanceOf(HTMLButtonElement)
    expect(button('Cancel')).toBeInstanceOf(HTMLButtonElement)
    setLanguage('zh-CN')
    await nextTick()

    expect(button('取消')).toBeInstanceOf(HTMLButtonElement)
    button('确认').click()
    expect(confirm).toHaveBeenCalledOnce()
  })
})
