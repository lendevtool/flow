<template>
  <div>
    <n-tooltip trigger="hover" placement="left">
      <template #trigger>
        <n-button
          id="layout-setting"
          type="primary"
          :aria-label="t('布局设置')"
          @click="modalRef.open()"
        >
          <template #icon>
            <i class="i-fe:settings" aria-hidden="true" />
          </template>
        </n-button>
      </template>
      {{ t('布局设置') }}
    </n-tooltip>

    <MeModal
      ref="modalRef"
      role="dialog"
      aria-modal="true"
      :aria-label="t('布局设置')"
      :title="t('布局设置')"
      :show-footer="false"
      width="min(600px, 92vw)"
    >
      <LayoutPreferences />
      <div v-if="permissionStore.hasRoute('/profile')" class="mt-20">
        <n-button size="small" secondary @click="appStore.layoutSettingVisible = false">
          {{ t('隐藏全局布局设置入口') }}
        </n-button>
        <p class="mt-8 text-13 opacity-70">
          {{ t('可在个人资料中重新显示此入口。') }}
        </p>
      </div>
    </MeModal>
  </div>
</template>

<script setup>
import { MeModal } from '@/components'
import { useModal } from '@/composables'
import { t } from '@/i18n'
import { useAppStore, usePermissionStore } from '@/store'
import LayoutPreferences from './LayoutPreferences.vue'

const appStore = useAppStore()
const permissionStore = usePermissionStore()
const [modalRef] = useModal()
</script>
