<template>
  <n-button
    circle quaternary size="small"
    :aria-label="t(targetLanguage === 'en-US' ? '切换至英文' : '切换至简体中文')"
    @click="setLanguage(targetLanguage)"
  >
    <template #icon>
      <span
        class="text-20"
        :class="targetLanguage === 'en-US' ? 'i-flag:us-4x3?bg' : 'i-flag:cn-4x3?bg'"
        aria-hidden="true"
      />
    </template>
  </n-button>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { language, setLanguage, t } from './index'

const targetLanguage = computed(() => language.value === 'zh-CN'
  ? 'en-US'
  : 'zh-CN')
</script>
