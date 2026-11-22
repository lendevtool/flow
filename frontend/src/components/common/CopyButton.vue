<template>
  <n-button
    quaternary circle size="tiny" :loading="copying" :disabled="!text || copying"
    :title="copied ? t('已复制') : (label ?? t('复制 JSON'))" :aria-label="copied ? t('已复制') : (label ?? t('复制 JSON'))"
    @click.stop="handleCopy"
  >
    <template #icon>
      <i :class="copied ? 'i-fe:check' : 'i-fe:copy'" aria-hidden="true" />
    </template>
  </n-button>
</template>

<script setup>
import { useClipboard } from '@vueuse/core'
import { t } from '@/i18n'

const props = defineProps({
  text: { type: String, default: '' },
  label: { type: String, default: undefined },
})
const { copy, copied } = useClipboard({ legacy: true })
const copying = ref(false)

async function handleCopy() {
  if (!props.text || copying.value)
    return
  copying.value = true
  try {
    await copy(props.text)
    $message.success(t('已复制'))
  }
  catch {
    $message.error(t('复制失败，请手动选择内容复制'))
  }
  finally {
    copying.value = false
  }
}
</script>
