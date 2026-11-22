<template>
  <n-avatar :size="size" :round="round" :src="imageSrc" aria-hidden="true">
    <template v-if="!imageSrc" #default>
      <i
        class="i-fe:user text-primary"
        style="font-size: var(--account-avatar-icon-size, calc(var(--n-merged-size) * 0.55))"
      />
    </template>
  </n-avatar>
</template>

<script setup lang="ts">
import type { AvatarProps } from 'naive-ui'
import { computed } from 'vue'
import { avatarImageSrc } from '@/utils/avatar'

const props = defineProps<{
  src?: string | null
  size?: AvatarProps['size']
  round?: boolean
}>()

const imageSrc = computed(() => avatarImageSrc(props.src))
</script>
