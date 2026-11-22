<template>
  <span v-if="path" class="sftp-path">
    <span class="sftp-directory" :title="directory">{{ directory }}</span>
    <CopyButton :text="path" :label="t('复制完整路径')" />
  </span>
  <span v-else>—</span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import CopyButton from '@/components/common/CopyButton.vue'
import { t } from '@/i18n'

const props = defineProps<{ path?: string }>()
const directory = computed(() => {
  const path = props.path ?? ''
  return path.slice(0, path.lastIndexOf('/') + 1)
})
</script>

<style scoped>
.sftp-path {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
}

.sftp-directory {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sftp-path :deep(button) {
  flex-shrink: 0;
}
</style>
