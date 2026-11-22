<template>
  <AppPage full>
    <iframe
      v-if="src"
      :src="src"
      :title="route.meta.title || 'Flow'"
      sandbox="allow-forms allow-scripts"
      referrerpolicy="no-referrer"
      frameborder="0"
      class="wh-full"
    />
  </AppPage>
</template>

<script setup>
import AppPage from '@/components/common/AppPage.vue'
import { externalNavigationTarget } from '@/utils/navigationTarget'

const route = useRoute()
const src = computed(() => {
  const target = externalNavigationTarget(route.meta.originPath)
  return target?.kind === 'web'
    ? target.url
    : undefined
})
</script>
