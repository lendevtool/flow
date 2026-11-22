<template>
  <div class="f-c-c">
    <n-tooltip trigger="hover" placement="bottom">
      <template #trigger>
        <div class="h-32 w-32">
          <n-color-picker
            :value="appStore.primaryColor"
            role="button"
            tabindex="0"
            :aria-label="t('设置主题色')"
            :swatches="primaryColors"
            :on-update:value="(v) => appStore.setPrimaryColor(v)"
            :render-label="() => ''"
            @keydown.enter.prevent="$event.currentTarget.click()"
            @keydown.space.prevent="$event.currentTarget.click()"
          />
        </div>
      </template>
      {{ t('设置主题色') }}
    </n-tooltip>
  </div>
</template>

<script setup>
import { getPresetColors } from '@arco-design/color'
import { t } from '@/i18n'
import { useAppStore } from '@/store'

const appStore = useAppStore()

const primaryColors = Object.entries(getPresetColors()).map(([, value]) => value.primary)
</script>
