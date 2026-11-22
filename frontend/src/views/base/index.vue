<template>
  <CommonPage show-footer>
    <n-space size="large">
      <n-card :title="t('按钮 Button')">
        <n-space>
          <n-button>Default</n-button>
          <n-button type="tertiary">
            Tertiary
          </n-button>
          <n-button type="primary">
            Primary
          </n-button>
          <n-button type="info">
            Info
          </n-button>
          <n-button type="success">
            Success
          </n-button>
          <n-button type="warning">
            Warning
          </n-button>
          <n-button type="error">
            Error
          </n-button>
        </n-space>
      </n-card>

      <n-card :title="t('带 Icon 的按钮')">
        <n-space>
          <n-button type="info">
            <i class="i-material-symbols:add mr-4 text-18" />
            {{ t('新增') }}
          </n-button>
          <n-button type="error">
            <i class="i-material-symbols:delete-outline mr-4 text-18" />
            {{ t('删除') }}
          </n-button>
          <n-button type="warning">
            <i class="i-material-symbols:edit-outline mr-4 text-18" />
            {{ t('编辑') }}
          </n-button>
          <n-button type="primary">
            <i class="i-majesticons:eye-line mr-4 text-18" />
            {{ t('查看') }}
          </n-button>
        </n-space>
      </n-card>
    </n-space>

    <n-space size="large" mt-30>
      <n-card min-w-340 :title="t('通知 Notification')">
        <n-space>
          <n-button @click="notify('info')">
            {{ t('信息') }}
          </n-button>
          <n-button @click="notify('success')">
            {{ t('成功') }}
          </n-button>
          <n-button @click="notify('warning')">
            {{ t('警告') }}
          </n-button>
          <n-button @click="notify('error')">
            {{ t('错误') }}
          </n-button>
        </n-space>
      </n-card>

      <n-card min-w-340 :title="t('确认弹窗 Dialog')">
        <n-button type="error" @click="handleDelete">
          <i class="i-mi:delete mr-4" />
          {{ t('删除') }}
        </n-button>
      </n-card>

      <n-card min-w-340 :title="t('消息提醒 Message')">
        <n-space>
          <n-button :loading="loading" type="primary" @click="handleLogin">
            <i v-show="!loading" class="i-mdi:login mr-4" />
            {{ t('登录') }}
          </n-button>
          <n-button type="error" @click="handleMultiMessage">
            {{ t('多个错误提醒') }}
          </n-button>
        </n-space>
      </n-card>
    </n-space>
  </CommonPage>
</template>

<script setup>
import { t } from '@/i18n'
import { sleep } from '@/utils'

function handleDelete() {
  $dialog.confirm({
    content: t('确认删除？'),
    confirm() {
      $message.success(t('删除成功'))
    },
    cancel() {
      $message.warning(t('已取消'))
    },
  })
}

const loading = ref(false)
async function handleLogin() {
  loading.value = true
  $message.loading(t('登录中...'), { key: 'login' })
  await sleep(2000)
  $message.error(t('登录失败'), { key: 'login' })
  await sleep(500)
  $message.loading(t('正在尝试重新登录...'), { key: 'login' })
  await sleep(2000)
  $message.success(t('登录成功'), { key: 'login' })
  loading.value = false
}

function handleMultiMessage() {
  $message.error([
    t('用户名不能为空！'),
    t('密码不能为空！'),
    t('密码必须大于6位！'),
  ])
}

function notify(type) {
  $notification[type]({
    content: t('说点啥呢'),
    meta: t('想不出来'),
    duration: 2500,
    keepAliveOnHover: true,
  })
}
</script>
