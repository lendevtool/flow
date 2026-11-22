<template>
  <n-card class="mt-20" :title="t('界面偏好')">
    <n-space vertical :size="16">
      <div class="flex flex-wrap items-center gap-12">
        <span>{{ t('主题') }}</span>
        <n-radio-group v-model:value="appStore.colorMode" size="small" :aria-label="t('主题')">
          <n-radio-button value="auto">
            {{ t('跟随系统') }}
          </n-radio-button>
          <n-radio-button value="light">
            {{ t('浅色') }}
          </n-radio-button>
          <n-radio-button value="dark">
            {{ t('深色') }}
          </n-radio-button>
        </n-radio-group>
      </div>
      <div class="flex items-center gap-12">
        <span>{{ t('设置主题色') }}</span>
        <ThemeSetting />
      </div>
      <div class="flex flex-wrap items-center gap-12">
        <span>{{ t('语言') }}</span>
        <n-radio-group :value="language" size="small" :aria-label="t('语言')" @update:value="setLanguage">
          <n-radio-button value="zh-CN">
            简体中文
          </n-radio-button>
          <n-radio-button value="en-US">
            English
          </n-radio-button>
        </n-radio-group>
      </div>
      <LayoutPreferences />
      <div class="flex flex-wrap items-center gap-12">
        <span>{{ t('显示全局布局设置入口') }}</span>
        <n-switch v-model:value="appStore.layoutSettingVisible" :aria-label="t('显示全局布局设置入口')" />
      </div>
    </n-space>
  </n-card>
</template>

<script setup lang="ts">
import LayoutPreferences from '@/components/common/LayoutPreferences.vue'
import ThemeSetting from '@/components/common/ThemeSetting.vue'
import { language, setLanguage, t } from '@/i18n'
import { useAppStore } from '@/store'

const appStore = useAppStore()
</script>
