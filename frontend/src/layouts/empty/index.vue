<template>
  <div class="fixed right-16 top-16 z-10 flex items-center gap-12">
    <RouterLink v-if="canOpenProfile" to="/profile" class="text-14 text-primary">
      {{ t('个人资料') }}
    </RouterLink>
    <LanguageSelect />
  </div>
  <slot />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { t } from '@/i18n'
import LanguageSelect from '@/i18n/LanguageSelect.vue'
import { usePermissionStore } from '@/store'

const route = useRoute()
const permissionStore = usePermissionStore()
const canOpenProfile = computed(() => route.path !== '/profile' && permissionStore.hasRoute('/profile'))
</script>
