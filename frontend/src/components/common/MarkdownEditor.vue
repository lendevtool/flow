<template>
  <section class="markdown-editor" @keydown="shortcut">
    <div class="tools">
      <n-tooltip v-for="tool in modes" :key="tool.id">
        <template #trigger>
          <n-button size="small" quaternary :type="preview === (tool.id === 'preview') ? 'primary' : 'default'" :aria-label="t(tool.label)" @click="setPreview(tool.id === 'preview')">
            <i :class="tool.icon" aria-hidden="true" />
          </n-button>
        </template>
        {{ t(tool.label) }}
      </n-tooltip>
      <n-dropdown trigger="click" :options="headings" @select="selectHeading">
        <n-button size="small" quaternary :disabled="preview || disabled" :aria-label="t('标题 H1–H5')">
          <i class="i-fe:type" aria-hidden="true" />
        </n-button>
      </n-dropdown>
      <n-tooltip v-for="tool in formats" :key="tool.id">
        <template #trigger>
          <n-button size="small" quaternary :disabled="preview || disabled" :aria-label="t(tool.label)" @click="format(tool.id)">
            <i :class="tool.icon" aria-hidden="true" />
          </n-button>
        </template>
        {{ t(tool.label) }} · {{ platformKey }}+{{ tool.shortcut }}
      </n-tooltip>
      <n-tooltip v-if="allowLink">
        <template #trigger>
          <n-button size="small" quaternary :disabled="preview || disabled" :aria-label="t('插入链接')" @click="insertLink">
            <i class="i-fe:link" aria-hidden="true" />
          </n-button>
        </template>
        {{ t('插入链接') }} · {{ platformKey }}+K
      </n-tooltip>
      <slot v-if="!preview && !disabled" name="tools" :insert-text="insertText" />
      <n-button class="help" size="small" quaternary :aria-label="t('编辑帮助')" @click="help = true">
        <i class="i-fe:help-circle" aria-hidden="true" />
      </n-button>
    </div>
    <div v-if="preview" ref="previewPanel" class="preview" tabindex="0">
      <slot name="preview" />
    </div>
    <textarea v-else ref="editor" :value="model" :maxlength="maxlength" :rows="rows" :disabled="disabled" :aria-label="label" @input="model = ($event.target as HTMLTextAreaElement).value" />
    <n-modal v-model:show="help" preset="card" :title="t('编辑帮助')" style="width: min(560px, calc(100vw - 32px))">
      <n-table size="small" :bordered="false">
        <thead><tr><th>{{ t('操作') }}</th><th>Windows</th><th>macOS</th></tr></thead>
        <tbody>
          <tr v-for="tool in formats" :key="tool.id">
            <td>{{ t(tool.label) }}</td><td>Ctrl+{{ tool.shortcut }}</td><td>⌘+{{ tool.shortcut }}</td>
          </tr>
          <tr v-if="allowLink">
            <td>{{ t('插入链接') }}</td><td>Ctrl+K</td><td>⌘+K</td>
          </tr>
          <tr><td>H1–H5</td><td>Ctrl+Alt+1–5</td><td>⌘+⌥+1–5</td></tr>
          <tr><td>{{ t('预览') }}</td><td>Ctrl+Shift+M</td><td>⌘+Shift+M</td></tr>
        </tbody>
      </n-table>
    </n-modal>
  </section>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { t } from '@/i18n'

type Format = 'bold' | 'italic' | 'unordered' | 'ordered' | 'code'
const props = withDefaults(defineProps<{
  label: string
  maxlength: number
  rows?: number
  allowLink?: boolean
  disabled?: boolean
}>(), { rows: 4 })

const model = defineModel<string>({ required: true })
const editor = ref<HTMLTextAreaElement>()
const previewPanel = ref<HTMLElement>()
const preview = ref(false)
const help = ref(false)
const platformKey = /Mac|iPhone|iPad/i.test(navigator.platform)
  ? '⌘'
  : 'Ctrl'
const headings = [1, 2, 3, 4, 5].map(level => ({ label: `H${level}`, key: level }))
const modes = [
  { id: 'edit', label: '编辑', icon: 'i-fe:edit' },
  { id: 'preview', label: '预览', icon: 'i-fe:eye' },
]
const formats: { id: Format, label: string, icon: string, shortcut: string }[] = [
  {
    id: 'bold',
    label: '加粗',
    icon: 'i-fe:bold',
    shortcut: 'B',
  },
  {
    id: 'italic',
    label: '斜体',
    icon: 'i-fe:italic',
    shortcut: 'I',
  },
  {
    id: 'unordered',
    label: '无序列表',
    icon: 'i-fe:list',
    shortcut: 'Shift+8',
  },
  {
    id: 'ordered',
    label: '有序列表',
    icon: 'i-material-symbols:format-list-numbered',
    shortcut: 'Shift+7',
  },
  {
    id: 'code',
    label: '行内代码',
    icon: 'i-fe:code',
    shortcut: 'E',
  },
]

function replace(start: number, end: number, value: string, selectionStart = 0, selectionEnd = value.length - selectionStart) {
  const input = editor.value
  if (!input || props.disabled)
    return
  const next = model.value.slice(0, start) + value + model.value.slice(end)
  if (next.length > input.maxLength)
    return
  model.value = next
  void nextTick(() => {
    input.focus()
    input.setSelectionRange(start + selectionStart, start + selectionEnd)
  })
}

function insertText(text: string) {
  const input = editor.value
  if (input)
    replace(input.selectionStart, input.selectionEnd, text, text.length, text.length)
}

function insertLink() {
  const input = editor.value
  if (!input)
    return
  const { selectionStart, selectionEnd } = input
  const label = model.value.slice(selectionStart, selectionEnd) || t('链接文字')
  const value = `[${label}](https://)`
  replace(selectionStart, selectionEnd, value, label.length + 3, value.length - 1)
}

function format(id: Format) {
  const input = editor.value
  if (!input)
    return
  if (id === 'unordered' || id === 'ordered') {
    lineFormat(index => id === 'ordered'
      ? `${index + 1}. `
      : '- ')
    return
  }
  const marker = { bold: '**', italic: '*', code: '`' }[id]
  const start = input.selectionStart
  const end = input.selectionEnd
  if (start >= marker.length && model.value.slice(start - marker.length, start) === marker && model.value.slice(end, end + marker.length) === marker) {
    replace(start - marker.length, end + marker.length, model.value.slice(start, end))
  }
  else {
    replace(start, end, marker + (model.value.slice(start, end) || t('文字')) + marker, marker.length)
  }
}

function lineFormat(prefix: (index: number) => string) {
  const input = editor.value
  if (!input)
    return
  const value = model.value
  const start = input.selectionStart === 0
    ? 0
    : value.lastIndexOf('\n', input.selectionStart - 1) + 1
  const last = input.selectionEnd > input.selectionStart && value[input.selectionEnd - 1] === '\n'
    ? input.selectionEnd - 1
    : input.selectionEnd
  const next = value.indexOf('\n', last)
  const end = next < 0
    ? value.length
    : next
  replace(start, end, value.slice(start, end).split('\n').map((line, index) =>
    prefix(index) + (line.replace(/^(?:#{1,5}|[-*]|\d+\.)\s+/, '') || t('文字'))).join('\n'))
}

function selectHeading(level: string | number) {
  heading(Number(level))
}

function heading(level: number) {
  lineFormat(() => `${'#'.repeat(level)} `)
}

function setPreview(enabled: boolean) {
  preview.value = enabled
  void nextTick(() => (enabled
    ? previewPanel.value
    : editor.value)?.focus())
}

function shortcut(event: KeyboardEvent) {
  if (event.isComposing || event.ctrlKey === event.metaKey)
    return
  if (event.shiftKey && !event.altKey && event.code === 'KeyM') {
    event.preventDefault()
    setPreview(!preview.value)
    return
  }
  if (event.target !== editor.value || props.disabled)
    return
  if (event.altKey && !event.shiftKey && /^Digit[1-5]$/.test(event.code)) {
    event.preventDefault()
    heading(Number(event.code.slice(-1)))
    return
  }
  if (event.altKey)
    return
  if (props.allowLink && !event.shiftKey && event.code === 'KeyK') {
    event.preventDefault()
    insertLink()
    return
  }
  const command = (event.shiftKey
    ? { Digit7: 'ordered', Digit8: 'unordered' }
    : { KeyB: 'bold', KeyI: 'italic', KeyE: 'code' }) as Record<string, Format>
  if (command[event.code]) {
    event.preventDefault()
    format(command[event.code])
  }
}
</script>

<style scoped>
.markdown-editor {
  border: 1px solid var(--n-border-color, #dce6df);
  border-radius: 7px;
  overflow: hidden;
  width: 100%;
}
.tools {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  padding: 5px;
  background: #8881;
}
.help {
  margin-left: auto;
}
textarea {
  display: block;
  width: 100%;
  min-height: 82px;
  resize: vertical;
  padding: 9px;
  color: inherit;
  background: transparent;
  outline-offset: -2px;
}
.preview {
  min-height: 82px;
  padding: 9px;
  overflow-wrap: anywhere;
}
</style>
