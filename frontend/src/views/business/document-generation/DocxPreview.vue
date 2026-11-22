<template>
  <n-modal :show="true" preset="card" :title="t('Word 预览')" class="docx-preview-dialog" @update:show="show => !show && emit('close')">
    <n-alert v-if="status === 'failed'" type="error" role="alert">
      {{ t('无法预览此 Word 文件，请下载后在 Word 中打开') }}
    </n-alert>
    <n-spin v-else :show="status === 'loading'" :description="t('正在渲染 Word 预览')">
      <iframe
        ref="frame"
        :srcdoc="source"
        :title="t('Word 预览')"
        sandbox="allow-scripts"
        referrerpolicy="no-referrer"
        class="docx-preview-frame"
        :style="{ visibility: status === 'ready' ? 'visible' : 'hidden' }"
        @load="render"
      />
    </n-spin>
    <template #footer>
      <n-space justify="end">
        <n-button @click="emit('close')">
          {{ t('关闭') }}
        </n-button>
        <n-button type="primary" @click="emit('download', file)">
          {{ t('下载 Word') }}
        </n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import type { DocxFile } from './api'
// 加载包内 UMD 源码，使用一次性 nonce 在不透明来源内执行。
import renderer from 'docx-preview-umd?raw'
import zip from 'jszip/dist/jszip.min.js?raw'
import { onBeforeUnmount, ref } from 'vue'
import { t } from '@/i18n'
import { createRandomKey } from './input'
import { connectPreview, createPreviewDocument } from './preview'
import loader from './preview-frame.js?raw'

const props = defineProps<{ file: DocxFile }>()
const emit = defineEmits<{ close: [], download: [file: DocxFile] }>()
const frame = ref<HTMLIFrameElement>()
const status = ref<'loading' | 'ready' | 'failed'>('loading')
const source = createPreviewDocument(createRandomKey(), zip, renderer, loader)
let session: ReturnType<typeof connectPreview> | undefined
let closed = false
let loaded = false

async function render() {
  const target = frame.value?.contentWindow
  if (loaded || !target)
    return
  loaded = true
  try {
    const bytes = await props.file.file.arrayBuffer()
    if (closed)
      return
    session = connectPreview(target, bytes)
    const success = await session.done
    if (!closed)
      status.value = success ? 'ready' : 'failed'
  }
  catch {
    if (!closed)
      status.value = 'failed'
  }
}

onBeforeUnmount(() => {
  closed = true
  session?.close()
})
</script>

<style scoped>
.docx-preview-dialog {
  width: min(1080px, 95vw);
}

.docx-preview-frame {
  display: block;
  width: 100%;
  height: min(68vh, 900px);
  border: 0;
  background: #eee;
}
</style>
