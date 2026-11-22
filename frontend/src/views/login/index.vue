<template>
  <div class="login-page relative isolate wh-full flex-col overflow-auto bg-#f5f7f8 px-16 pt-56 dark:bg-#111316">
    <LoginBackground class="pointer-events-none absolute inset-0 z--1 wh-full text-primary" />
    <div
      class="m-auto max-w-360 w-full f-c-c rounded-16 auto-bg p-12 card-shadow md:max-w-700"
    >
      <div class="hidden min-w-0 flex-1 px-12 md:block">
        <LoginWorkflowArt class="block h-auto w-full text-primary" />
      </div>

      <div class="max-w-320 w-full flex-col px-20 py-32">
        <h2 class="f-c-c text-24 text-#454a50 font-normal dark:text-#e4e4e7">
          <img src="@/assets/images/flow-logo.svg" alt="Flow" class="mr-12 h-50 w-50 rounded-4 bg-primary">
          {{ title }}
        </h2>
        <div v-if="mfaResult" class="mt-24">
          <MfaRecoveryCodes :codes="mfaResult.recoveryCodes" @done="finishMfaLogin" />
        </div>
        <div v-else-if="mfaChallenge" class="mt-24">
          <MfaChallengeForm
            :challenge="mfaChallenge"
            :loading="loading"
            @verify="verifyMfa"
            @cancel="cancelMfa"
          />
        </div>
        <template v-else-if="authStore.passwordChangeRequired">
          <n-alert type="warning" class="mt-24">
            {{ t('请先修改密码') }}
          </n-alert>
          <n-input
            v-model:value="passwordChange.oldPassword"
            class="mt-20"
            type="password"
            :maxlength="72"
            :placeholder="t('请输入原密码')"
            show-password-on="click"
          />
          <n-input
            v-model:value="passwordChange.newPassword"
            class="mt-12"
            type="password"
            :placeholder="t('请输入新密码')"
            :input-props="{ autocomplete: 'new-password' }"
            show-password-on="click"
            @keydown.enter="changePassword"
          />
          <PasswordRules class="mt-12" />
          <n-alert v-if="passwordError" type="error" class="mt-12">
            {{ passwordError }}
          </n-alert>
          <n-space class="mt-20">
            <n-button type="primary" :loading="loading" @click="changePassword">
              {{ t('修改密码') }}
            </n-button>
            <n-button :disabled="loading" @click="leaveChangePassword">
              {{ t('退出登录') }}
            </n-button>
          </n-space>
        </template>
        <template v-else>
          <n-radio-group
            v-if="localEnabled && domainEnabled"
            :value="loginInfo.loginMethod"
            :disabled="loading"
            :aria-label="t('登录方式')"
            class="mt-24"
            @update:value="setLoginMethod"
          >
            <n-radio value="LOCAL">
              {{ t('本地登录') }}
            </n-radio>
            <n-radio value="DOMAIN">
              {{ t('域登录') }}
            </n-radio>
          </n-radio-group>
          <p v-else-if="domainEnabled" class="mt-24 text-14">
            {{ t('使用微软域账号登录') }}
          </p>
          <n-input
            v-model:value="loginInfo.username"
            autofocus
            class="mt-32 h-40 items-center"
            :placeholder="t(loginInfo.loginMethod === 'DOMAIN' ? '请输入域账号' : '请输入用户名')"
            :maxlength="loginInfo.loginMethod === 'DOMAIN' ? 254 : 50"
          >
            <template #prefix>
              <i class="i-fe:user mr-12 opacity-20" />
            </template>
          </n-input>
          <n-input
            v-model:value="loginInfo.password"
            class="mt-20 h-40 items-center"
            type="password"
            show-password-on="click"
            :placeholder="t('请输入密码')"
            :maxlength="loginInfo.loginMethod === 'DOMAIN' ? 256 : 40"
            @keydown.enter="handleLogin()"
          >
            <template #prefix>
              <i class="i-fe:lock mr-12 opacity-20" />
            </template>
          </n-input>

          <div v-if="captchaRequired" class="mt-20 flex items-center">
            <n-input
              v-model:value="loginInfo.captcha"
              class="h-40 items-center"
              :placeholder="t('请输入验证码')"
              :maxlength="4"
              @keydown.enter="handleLogin()"
            >
              <template #prefix>
                <i class="i-fe:key mr-12 opacity-20" />
              </template>
            </n-input>
            <img
              v-if="captchaUrl"
              :src="captchaUrl"
              :alt="t('验证码')"
              height="40"
              class="ml-12 w-80 cursor-pointer"
              @click="initCaptcha"
            >
          </div>

          <n-checkbox
            class="mt-20"
            :checked="isRemember"
            :label="t('记住用户名')"
            :on-update:checked="(val) => (isRemember = val)"
          />

          <div class="mt-20 flex items-center">
            <n-button
              class="h-40 flex-1 rounded-5 text-16"
              type="primary"
              :loading="loading"
              @click="handleLogin()"
            >
              {{ t('登录') }}
            </n-button>
          </div>
        </template>
      </div>
    </div>

    <TheFooter class="py-12 dark:text-#a1a1aa" />
  </div>
</template>

<script setup>
import { useStorage } from '@vueuse/core'
import mfaApi from '@/api/mfa'
import { passwordApi, passwordChangeError } from '@/api/password'
import PasswordRules from '@/components/common/PasswordRules.vue'
import MfaChallengeForm from '@/components/mfa/MfaChallengeForm.vue'
import MfaRecoveryCodes from '@/components/mfa/MfaRecoveryCodes.vue'
import { t } from '@/i18n'
import { useAuthStore } from '@/store'
import { lStorage, throttle } from '@/utils'
import { internalNavigationPath } from '@/utils/navigationTarget'
import api from './api'
import LoginBackground from './LoginBackground.vue'
import LoginWorkflowArt from './LoginWorkflowArt.vue'

const authStore = useAuthStore()
const router = useRouter()
const route = useRoute()
const title = import.meta.env.VITE_TITLE

const loginInfo = ref({
  username: '',
  password: '',
  loginMethod: 'LOCAL',
})

const captchaRequired = ref(true)
const localEnabled = ref(true)
const domainEnabled = ref(false)
const captchaUrl = ref('')
const passwordChange = ref({ oldPassword: '', newPassword: '' })
const passwordError = ref('')
const mfaChallenge = ref(null)
const mfaResult = ref(null)
const initCaptcha = throttle(() => {
  captchaUrl.value = captchaRequired.value
    ? `${import.meta.env.VITE_AXIOS_BASE_URL}/auth/captcha?${Date.now()}`
    : ''
}, 500)

const localLoginInfo = lStorage.get('loginInfo')
if (localLoginInfo) {
  loginInfo.value.username = localLoginInfo.username || ''
  loginInfo.value.loginMethod = localLoginInfo.loginMethod === 'DOMAIN'
    ? 'DOMAIN'
    : 'LOCAL'
  // 清理旧版本缓存中的明文密码。
  lStorage.set('loginInfo', { username: loginInfo.value.username, loginMethod: loginInfo.value.loginMethod })
}
function setLoginMethod(method) {
  if (loginInfo.value.loginMethod === method)
    return
  loginInfo.value.loginMethod = method
  loginInfo.value.username = localLoginInfo?.loginMethod === method
    ? localLoginInfo.username
    : ''
  loginInfo.value.password = ''
}

async function loadLoginOptions() {
  try {
    const { data } = await api.options()
    captchaRequired.value = data.captchaRequired
    localEnabled.value = data.localEnabled !== false
    domainEnabled.value = data.domainEnabled === true
    if (!localEnabled.value)
      setLoginMethod('DOMAIN')
    else if (!domainEnabled.value)
      setLoginMethod('LOCAL')
  }
  catch {
    captchaRequired.value = true
  }
  initCaptcha()
}

watch(() => authStore.passwordChangeRequired, (required) => {
  if (!required)
    loadLoginOptions()
}, { immediate: true })

const isRemember = useStorage('isRemember', true)
const loading = ref(false)
async function handleLogin() {
  if (loading.value)
    return
  const { username, password, captcha, loginMethod } = loginInfo.value
  if (!username || !password)
    return $message.warning(t('请输入用户名和密码'))
  if (captchaRequired.value && !captcha)
    return $message.warning(t('请输入验证码'))
  try {
    loading.value = true
    $message.loading(t('正在验证，请稍后...'), { key: 'login' })
    const { data } = await api.login({
      username,
      loginMethod,
      password: password.toString(),
      captcha,
    })
    if (isRemember.value) {
      lStorage.set('loginInfo', { username, loginMethod })
    }
    else {
      lStorage.remove('loginInfo')
    }
    loginInfo.value.password = ''
    loginInfo.value.captcha = ''
    if (data.mfaChallenge) {
      mfaChallenge.value = data.mfaChallenge
      $message.destroy('login')
      return
    }
    await onLoginSuccess(data)
  }
  catch {
    loginInfo.value.captcha = ''
    await loadLoginOptions()
    $message.destroy('login')
  }
  finally {
    loading.value = false
  }
}

async function verifyMfa(code) {
  if (!mfaChallenge.value || loading.value)
    return
  loading.value = true
  try {
    const { data } = await mfaApi.verifyLogin(mfaChallenge.value.ticket, code)
    mfaChallenge.value = null
    if (data.recoveryCodes.length)
      mfaResult.value = data
    else
      await onLoginSuccess(data)
  }
  catch {
    // 统一拦截器显示验证失败，保留挑战供重试。
  }
  finally {
    loading.value = false
  }
}

async function cancelMfa() {
  mfaChallenge.value = null
  await loadLoginOptions()
}

async function finishMfaLogin() {
  const result = mfaResult.value
  mfaResult.value = null
  await onLoginSuccess(result)
}

async function changePassword() {
  if (loading.value)
    return
  const { oldPassword, newPassword } = passwordChange.value
  if (!oldPassword || !newPassword) {
    passwordError.value = t('请输入原密码和新密码')
    return
  }
  passwordError.value = ''
  loading.value = true
  try {
    await passwordApi.change({ oldPassword, newPassword })
    $message.success(t('密码已更新，请重新登录'))
    passwordChange.value = { oldPassword: '', newPassword: '' }
    await authStore.logout()
  }
  catch (error) {
    passwordError.value = passwordChangeError(error)
  }
  finally {
    loading.value = false
  }
}

async function leaveChangePassword() {
  try {
    await api.logout()
  }
  finally {
    passwordError.value = ''
    passwordChange.value = { oldPassword: '', newPassword: '' }
    await authStore.logout()
  }
}

async function onLoginSuccess(data = {}) {
  authStore.setToken(data)
  if (data.passwordChangeRequired) {
    $message.destroy('login')
    return
  }
  $message.loading(t('登录中...'), { key: 'login' })
  try {
    $message.success(t('登录成功'), { key: 'login' })
    const { redirect, ...query } = route.query
    const path = internalNavigationPath(redirect)
    if (path) {
      await router.push({ path, query })
    }
    else {
      await router.push('/')
    }
  }
  catch {
    $message.destroy('login')
  }
}
</script>

<style scoped>
.login-page {
  --login-art-surface: #fff;
}

:global(.dark .login-page) {
  --login-art-surface: #202127;
}
</style>
