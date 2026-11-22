<template>
  <div class="flex-col gap-12">
    <n-alert type="warning" :title="t('请保存恢复码')">
      {{ t('每组恢复码仅可使用一次，关闭后无法再次查看。') }}
    </n-alert>
    <pre class="m-0 select-text rounded-6 bg-gray-500/10 p-12 text-center"><code>{{ codes.join('\n') }}</code></pre>
    <n-button type="primary" @click="emit('done')">
      {{ t('已保存恢复码') }}
    </n-button>
  </div>
</template>

<script setup lang="ts">
import { t } from '@/i18n'

defineProps<{ codes: string[] }>()
const emit = defineEmits<{ done: [] }>()
</script>
