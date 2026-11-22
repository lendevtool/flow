<template>
  <i
    id="toggleTheme"
    class="mr-16 cursor-pointer"
    :class="appStore.isDark ? 'i-fe:moon' : 'i-fe:sun'"
    @click="toggleDark"
  />
</template>

<script setup>
import { useAppStore } from '@/store'

const appStore = useAppStore()
async function toggleDark({ clientX, clientY }) {
  if (!document.startViewTransition) {
    return appStore.toggleDark()
  }

  const clipPath = [
    `circle(0px at ${clientX}px ${clientY}px)`,
    `circle(${Math.hypot(
      Math.max(clientX, window.innerWidth - clientX),
      Math.max(clientY, window.innerHeight - clientY),
    )}px at ${clientX}px ${clientY}px)`,
  ]

  await document.startViewTransition(() => appStore.toggleDark()).ready

  document.documentElement.animate(
    {
      clipPath: appStore.isDark
        ? clipPath.reverse()
        : clipPath,
    },
    {
      duration: 500,
      easing: 'ease-in',
      pseudoElement: `::view-transition-${appStore.isDark ? 'old' : 'new'}(root)`,
      fill: 'both',
    },
  )
}
</script>
