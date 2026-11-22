<template>
  <AppPage show-footer>
    <n-card>
      <n-space align="center">
        <AccountAvatar round :size="100" :src="userStore.avatar" />
        <div class="ml-20">
          <div class="flex items-center text-16">
            <span>{{ t('用户名:') }}</span>
            <span class="ml-12 opacity-80">{{ userStore.username }}</span>
            <n-button v-if="userStore.userInfo?.loginMethod !== 'DOMAIN'" class="ml-32" type="primary" text @click="openPasswordModal">
              <i class="i-fe:edit mr-4" />
              {{ t('修改密码') }}
            </n-button>
          </div>
          <div class="mt-16 flex items-center">
            <n-button type="primary" ghost @click="avatarModalRef.open()">
              {{ t('更改头像') }}
            </n-button>
            <span class="ml-12 opacity-60">
              {{ t('修改头像只支持在线链接，不提供上传图片功能，如有需要可自行对接！') }}
            </span>
          </div>
        </div>
      </n-space>
    </n-card>

    <n-card class="mt-20" :title="t('个人资料信息')">
      <template #header-extra>
        <n-button type="primary" text @click="profileModalRef.open()">
          <i class="i-fe:edit mr-4" />
          {{ t('修改资料') }}
        </n-button>
      </template>

      <n-descriptions
        label-placement="left"
        :label-style="{ width: '200px', textAlign: 'center' }"
        :column="1"
        bordered
      >
        <n-descriptions-item :label="t('昵称')">
          {{ userStore.displayName }}
        </n-descriptions-item>
        <n-descriptions-item :label="t('性别')">
          {{ genders.find((item) => item.value === userStore.userInfo?.gender)?.label ?? t('未知') }}
        </n-descriptions-item>
        <n-descriptions-item :label="t('地址')">
          {{ userStore.userInfo?.address }}
        </n-descriptions-item>
        <n-descriptions-item :label="t('邮箱')">
          {{ userStore.userInfo?.email }}
        </n-descriptions-item>
      </n-descriptions>
    </n-card>

    <AppearanceSettings />
    <MfaSettings />
    <WorkflowDelegation />

    <MeModal ref="avatarModalRef" width="420px" :title="t('更改头像')" @ok="handleAvatarSave()">
      <n-input v-model:value="newAvatar" />
    </MeModal>

    <MeModal ref="pwdModalRef" :title="t('修改密码')" width="min(420px, calc(100vw - 32px))" @ok="handlePwdSave()">
      <n-form
        ref="pwdFormRef"
        :model="pwdForm"
        label-placement="left"
        require-mark-placement="left"
      >
        <n-form-item :label="t('原密码')" path="oldPassword" :rule="required">
          <n-input v-model:value="pwdForm.oldPassword" type="password" :placeholder="t('请输入原密码')" show-password-on="mousedown" />
        </n-form-item>
        <n-form-item :label="t('新密码')" path="newPassword" :rule="required">
          <n-input
            v-model:value="pwdForm.newPassword"
            type="password"
            :placeholder="t('请输入新密码')"
            :input-props="{ autocomplete: 'new-password' }"
            show-password-on="mousedown"
          />
        </n-form-item>
      </n-form>
      <PasswordRules />
      <n-alert v-if="passwordError" type="error" class="mt-12">
        {{ passwordError }}
      </n-alert>
    </MeModal>

    <MeModal ref="profileModalRef" :title="t('修改资料')" width="420px" @ok="handleProfileSave()">
      <n-form ref="profileFormRef" :model="profileForm" label-placement="left">
        <n-form-item :label="t('昵称')" path="nickName">
          <n-input v-model:value="profileForm.nickName" :placeholder="t('请输入昵称')" />
        </n-form-item>
        <n-form-item :label="t('性别')" path="gender">
          <n-select
            v-model:value="profileForm.gender"
            :options="genders"
            :placeholder="t('请选择性别')"
          />
        </n-form-item>
        <n-form-item :label="t('地址')" path="address">
          <n-input v-model:value="profileForm.address" :placeholder="t('请输入地址')" />
        </n-form-item>
        <n-form-item :label="t('邮箱')" path="email">
          <n-input v-model:value="profileForm.email" :placeholder="t('请输入邮箱')" />
        </n-form-item>
      </n-form>
    </MeModal>
  </AppPage>
</template>

<script setup>
import { passwordApi, passwordChangeError } from '@/api/password'
import { MeModal } from '@/components'
import AccountAvatar from '@/components/common/AccountAvatar.vue'
import PasswordRules from '@/components/common/PasswordRules.vue'
import { useForm, useModal } from '@/composables'
import { t } from '@/i18n'
import { useAuthStore, useUserStore } from '@/store'
import { getUserInfo } from '@/store/helper'
import { avatarImageSrc } from '@/utils/avatar'
import api from './api'
import AppearanceSettings from './AppearanceSettings.vue'
import MfaSettings from './MfaSettings.vue'
import WorkflowDelegation from './WorkflowDelegation.vue'

const userStore = useUserStore()
const authStore = useAuthStore()
const required = computed(() => ({
  required: true,
  message: t('此为必填项'),
  trigger: ['blur', 'change'],
}))

const [pwdModalRef, pwdSaving] = useModal()
const [pwdFormRef, pwdForm, pwdValidation] = useForm({ oldPassword: '', newPassword: '' })
const passwordError = ref('')

function openPasswordModal() {
  if (pwdSaving.value)
    return
  pwdForm.value = { oldPassword: '', newPassword: '' }
  passwordError.value = ''
  pwdModalRef.value.open()
}

async function handlePwdSave() {
  if (pwdSaving.value)
    return false
  pwdSaving.value = true
  passwordError.value = ''
  try {
    try {
      await pwdValidation()
    }
    catch {
      return false
    }
    await passwordApi.change(pwdForm.value)
  }
  catch (error) {
    passwordError.value = passwordChangeError(error)
    return false
  }
  finally {
    pwdSaving.value = false
  }
  pwdForm.value = { oldPassword: '', newPassword: '' }
  $message.success(t('密码已更新，请重新登录'))
  await authStore.logout()
}

const newAvatar = ref(avatarImageSrc(userStore.avatar) ?? '')
const [avatarModalRef] = useModal()
async function handleAvatarSave() {
  if (!newAvatar.value) {
    $message.error(t('请输入头像地址'))
    return false
  }
  await api.updateProfile({ id: userStore.userId, avatar: newAvatar.value })
  $message.success(t('头像修改成功'))
  refreshUserInfo()
}

const genders = computed(() => [
  {
    label: t('保密'),
    value: 0,
  },
  {
    label: t('男'),
    value: 1,
  },
  {
    label: t('女'),
    value: 2,
  },
])
const [profileModalRef] = useModal()
const [profileFormRef, profileForm, profileValidation] = useForm({
  id: userStore.userId,
  nickName: userStore.nickName,
  gender: userStore.userInfo?.gender ?? 0,
  address: userStore.userInfo?.address,
  email: userStore.userInfo?.email,
})
async function handleProfileSave() {
  await profileValidation()
  await api.updateProfile(profileForm.value)
  $message.success(t('资料修改成功'))
  refreshUserInfo()
}

async function refreshUserInfo() {
  const user = await getUserInfo()
  userStore.setUser(user)
}
</script>
