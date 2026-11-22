<template>
  <AppCard class="flex items-center px-12" border-b="1px solid light_border dark:dark_border">
    <MenuCollapse />

    <BreadCrumb />

    <div class="ml-auto flex flex-shrink-0 items-center px-12 text-18">
      <BeginnerGuide />

      <ToggleTheme />

      <Fullscreen />

      <i
        class="i-fe:github mr-16 cursor-pointer"
        @click="handleLinkClick('https://github.com/zclzone/vue-naive-admin/tree/2.x')"
      />
      <i
        class="i-fe:git-branch mr-16 cursor-pointer"
        @click="handleLinkClick('https://gitee.com/isme-admin/vue-naive-admin/tree/2.x')"
      />

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
import { BeginnerGuide, BreadCrumb, Fullscreen, MenuCollapse, UserAvatar } from '@/layouts/components'
import NotificationBell from '@/layouts/components/NotificationBell.vue'

function handleLinkClick(link) {
  window.open(link, '_blank', 'noopener,noreferrer')
}
</script>
