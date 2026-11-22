<template>
  <n-dropdown :options="options" @select="handleSelect">
    <div id="user-dropdown" class="flex cursor-pointer items-center">
      <AccountAvatar round :size="36" :src="userStore.avatar" />
      <div v-if="userStore.userInfo" class="ml-12 flex-col flex-shrink-0 items-center">
        <span class="text-14">{{ userStore.displayName }}</span>
        <span class="text-12 opacity-50">[{{ navigationLabel(userStore.currentRole?.code, userStore.currentRole?.name, userStore.currentRole?.nameEn) }}]</span>
      </div>
    </div>
  </n-dropdown>

  <RoleSelect ref="roleSelectRef" />
</template>

<script setup>
import api from '@/api'
import AccountAvatar from '@/components/common/AccountAvatar.vue'
import { openPersonDetails } from '@/components/common/personDetails'
import { t } from '@/i18n'
import { navigationLabel } from '@/i18n/navigation'
import { RoleSelect } from '@/layouts/components'
import { useAuthStore, usePermissionStore, useUserStore } from '@/store'

const router = useRouter()
const userStore = useUserStore()
const authStore = useAuthStore()
const permissionStore = usePermissionStore()

const options = computed(() => [
  {
    label: t('人员信息'),
    key: 'person',
    show: !!userStore.userId,
  },
  {
    label: t('个人资料'),
    key: 'profile',
    icon: () => h('i', { class: 'i-material-symbols:person-outline text-14' }),
    show: permissionStore.hasRoute('/profile'),
  },
  {
    label: t('切换角色'),
    key: 'toggleRole',
    icon: () => h('i', { class: 'i-basil:exchange-solid text-14' }),
    show: userStore.roles.length > 1,
  },
  {
    label: t('退出登录'),
    key: 'logout',
    icon: () => h('i', { class: 'i-mdi:exit-to-app text-14' }),
  },
])

const roleSelectRef = ref(null)
function handleSelect(key) {
  switch (key) {
    case 'person':
      if (userStore.userId != null)
        openPersonDetails(userStore.userId, userStore.displayName)
      break
    case 'profile':
      router.push('/profile')
      break
    case 'toggleRole':
      roleSelectRef.value?.open({
        onOk() {
          location.reload()
        },
      })
      break
    case 'logout':
      $dialog.confirm({
        title: t('提示'),
        type: 'info',
        content: t('确认退出？'),
        async confirm() {
          try {
            await api.logout()
          }
          catch (error) {
            console.error(error)
          }
          authStore.logout()
          $message.success(t('已退出登录'))
        },
      })
      break
  }
}
</script>
