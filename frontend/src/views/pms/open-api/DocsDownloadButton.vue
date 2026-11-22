<template>
  <n-dropdown trigger="click" :options="options" :disabled="loading" @select="select">
    <n-button :text="text" :type="text ? 'primary' : 'default'" :loading="loading" :disabled="loading">
      <template #icon>
        <i class="i-fe:download" aria-hidden="true" />
      </template>
      {{ t('下载 OpenAPI 文档') }}
    </n-button>
  </n-dropdown>
</template>

<script setup lang="ts">
import type { DocumentFormat } from './documentDownload'
import { t } from '@/i18n'

defineProps<{ loading: boolean, text?: boolean }>()
const emit = defineEmits<{ download: [format: DocumentFormat] }>()
const options = [
  { label: 'JSON (.json)', key: 'json' },
  { label: 'Markdown (.md)', key: 'markdown' },
]

function select(key: string) {
  if (key === 'json' || key === 'markdown')
    emit('download', key)
}
</script>
