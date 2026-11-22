<template>
  <MarkdownEditor v-model="model" class="workflow-opinion" :label="t('审批意见')" :maxlength="500">
    <template #preview>
      <div class="workflow-markdown" v-html="renderOpinion(model)" />
    </template>
  </MarkdownEditor>
</template>

<script setup lang="ts">
import MarkdownEditor from '@/components/common/MarkdownEditor.vue'
import { t } from '@/i18n'
import { renderOpinion } from './markdown'

const model = defineModel<string>({ required: true })
</script>

<style scoped>
.workflow-markdown :deep(h1),
.workflow-markdown :deep(h2),
.workflow-markdown :deep(h3),
.workflow-markdown :deep(h4),
.workflow-markdown :deep(h5) {
  font-weight: 650;
}
.workflow-markdown :deep(ul),
.workflow-markdown :deep(ol) {
  padding-left: 22px;
  list-style: revert;
}
</style>
