<template>
  <CommonPage :title="t('邮件管理')">
    <template #action>
      <n-button v-if="activeTab === 'account' && can('MailAccount')" type="primary" @click="accounts?.open()">
        <i class="i-material-symbols:add mr-4 text-18" aria-hidden="true" />
        {{ t('新增邮箱') }}
      </n-button>
      <n-button v-if="activeTab === 'template' && can('MailTemplate')" type="primary" @click="templates?.open()">
        <i class="i-material-symbols:add mr-4 text-18" aria-hidden="true" />
        {{ t('新增模板') }}
      </n-button>
    </template>
    <n-tabs v-if="visibleTabs.length" v-model:value="activeTab" type="line" class="mail-tabs">
      <n-tab-pane v-if="can('MailAccount')" name="account" :tab="t('邮箱配置')" display-directive="show:lazy">
        <MailAccounts ref="accounts" />
      </n-tab-pane>
      <n-tab-pane v-if="can('MailTemplate')" name="template" :tab="t('邮件模板')" display-directive="show:lazy">
        <MailTemplates ref="templates" :can-send="can('MailSend')" />
      </n-tab-pane>
      <n-tab-pane v-if="can('MailLog')" name="log" :tab="t('发送日志')" display-directive="show:lazy">
        <MailLogs />
      </n-tab-pane>
    </n-tabs>
    <n-empty v-else :description="t('没有权限')" />
  </CommonPage>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import CommonPage from '@/components/common/CommonPage.vue'
import { t } from '@/i18n'
import { useUserStore } from '@/store'
import MailAccounts from './MailAccounts.vue'
import MailLogs from './MailLogs.vue'
import MailTemplates from './MailTemplates.vue'

defineOptions({ name: 'MailMgt' })

const route = useRoute()
const user = useUserStore()
const accounts = ref<InstanceType<typeof MailAccounts>>()
const templates = ref<InstanceType<typeof MailTemplates>>()
const tabs = [
  { key: 'account', permission: 'MailAccount' },
  { key: 'template', permission: 'MailTemplate' },
  { key: 'log', permission: 'MailLog' },
] as const

function can(code: typeof tabs[number]['permission'] | 'MailSend') {
  const buttons = route.meta.btns
  return user.currentRole.code === 'SUPER_ADMIN'
    || (Array.isArray(buttons) && buttons.some(button => button.code === code))
}

const visibleTabs = computed(() => tabs.filter(tab => can(tab.permission)))
const activeTab = ref<typeof tabs[number]['key']>()
watch(visibleTabs, (allowed) => {
  if (!allowed.some(tab => tab.key === activeTab.value))
    activeTab.value = allowed[0]?.key
}, { immediate: true })
</script>

<style scoped>
.mail-tabs {
  height: 100%;
  min-height: 420px;
}

.mail-tabs :deep(.n-tab-pane) {
  flex: 1;
  min-height: 0;
}
</style>
