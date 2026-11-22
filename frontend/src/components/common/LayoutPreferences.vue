<template>
  <div>
    <div class="mb-8 font-medium">
      {{ t('页面布局') }}
    </div>
    <n-space role="group" :aria-label="t('页面布局')">
      <n-button
        v-for="option in layouts"
        :key="option.value"
        class="h-auto py-8"
        :type="appStore.layout === option.value ? 'primary' : 'default'"
        :aria-pressed="appStore.layout === option.value"
        @click="appStore.setLayout(option.value)"
      >
        <span class="flex flex-col items-center gap-8">
          <span class="flex gap-4" aria-hidden="true">
            <n-skeleton v-if="option.value !== 'empty'" :width="20" :height="60" />
            <span class="flex flex-col gap-4">
              <n-skeleton v-for="height in option.rows" :key="height" :width="option.value === 'empty' ? 104 : 80" :height="height" />
            </span>
          </span>
          {{ t(option.label) }}
        </span>
      </n-button>
    </n-space>
    <p class="mt-8 text-13 opacity-70">
      {{ t('注: 此设置仅对未设置layout或者设置成跟随系统的页面有效，菜单设置的layout优先级最高') }}
    </p>
    <div class="mt-16 flex flex-wrap items-center gap-12">
      <span>{{ t('列表布局') }}</span>
      <n-radio-group v-model:value="appStore.tableSize" size="small" :aria-label="t('列表布局')">
        <n-radio-button value="small">
          {{ t('紧凑') }}
        </n-radio-button>
        <n-radio-button value="medium">
          {{ t('默认') }}
        </n-radio-button>
        <n-radio-button value="large">
          {{ t('宽松') }}
        </n-radio-button>
      </n-radio-group>
    </div>
  </div>
</template>

<script setup lang="ts">
import { t } from '@/i18n'
import { useAppStore } from '@/store'

const appStore = useAppStore()
const layouts = [
  {
    value: 'simple',
    label: '简约',
    rows: [60],
  },
  {
    value: 'normal',
    label: '通用',
    rows: [10, 46],
  },
  {
    value: 'full',
    label: '全面',
    rows: [6, 4, 42],
  },
  {
    value: 'empty',
    label: '空白',
    rows: [60],
  },
]
</script>
