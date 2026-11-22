<template>
  <SideLogo border-b="1px solid light_border dark:dark_border" />
  <SideMenu class="cus-scroll-y mt-4 h-0 flex-1" />
  <div class="my-12 flex flex-wrap items-center justify-around gap-8 px-12">
    <UserAvatar v-if="!appStore.collapsed" />
    <NotificationBell />
    <LanguageSelect />
    <MenuCollapse />
  </div>
</template>

<script setup>
import LanguageSelect from '@/i18n/LanguageSelect.vue'
import { MenuCollapse, SideLogo, SideMenu, UserAvatar } from '@/layouts/components'
import NotificationBell from '@/layouts/components/NotificationBell.vue'
import { useAppStore } from '@/store'

const appStore = useAppStore()
</script>
