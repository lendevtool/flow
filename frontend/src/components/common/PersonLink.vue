<template>
  <button v-if="canView" type="button" class="person-link" :aria-label="ariaLabel" @click.stop="open">
    <slot>{{ label }}</slot>
  </button>
  <span v-else><slot>{{ label }}</slot></span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { usePermissionStore, useUserStore } from '@/store'
import { openPersonDetails } from './personDetails'

const props = defineProps<{
  userId?: number | string | null
  label: string
  ariaLabel?: string
}>()

const user = useUserStore()
const permission = usePermissionStore()
const canView = computed(() => {
  const id = String(props.userId ?? '')
  return /^[1-9]\d*$/.test(id) && (
    id === String(user.userId)
    || user.currentRole.code === 'SUPER_ADMIN'
    || permission.resourcesByCode.has('DepartmentView')
  )
})

function open() {
  if (props.userId != null)
    openPersonDetails(props.userId, props.label)
}
</script>

<style scoped>
.person-link {
  display: inline-block;
  max-width: 100%;
  padding: 0;
  overflow: hidden;
  border: 0;
  background: transparent;
  color: rgb(var(--primary-color));
  font: inherit;
  text-align: left;
  text-overflow: ellipsis;
  vertical-align: middle;
  white-space: nowrap;
  cursor: pointer;
}

.person-link:hover,
.person-link:focus-visible {
  text-decoration: underline;
}
</style>
