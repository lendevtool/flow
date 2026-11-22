<template>
  <AppCard class="flex items-center px-12" border-b="1px solid light_border dark:dark_border">
    <MenuCollapse />

    <AppTab class="w-0 flex-1 px-12" />

    <span class="mx-6 opacity-20">|</span>

    <div class="flex flex-shrink-0 items-center px-12 text-18">
      <ToggleTheme />

      <Fullscreen />

      <ThemeSetting id="theme-setting" class="mr-16" />

      <LanguageSelect class="mr-12" />
      <NotificationBell class="mr-12" />
      <UserAvatar />
    </div>
  </AppCard>
</template>

<script setup>
import { ToggleTheme } from '@/components'
import LanguageSelect from '@/i18n/LanguageSelect.vue'
import { AppTab, Fullscreen, MenuCollapse, UserAvatar } from '@/layouts/components'
import NotificationBell from '@/layouts/components/NotificationBell.vue'
</script>
