<template>
  <div class="h-full flex flex-col">
    <n-alert v-if="listFailed" type="error" class="mb-12 shrink-0">
      {{ t('加载失败') }}
      <n-button text @click="table?.handleSearch()">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <MeCrud
      ref="table"
      v-model:query-items="filters"
      :columns="columns"
      :get-data="api.accounts"
      :page-size="20"
      :scroll-x="960"
      class="min-h-0 flex-1"
      @update:query-failed="listFailed = $event"
    >
      <MeQueryItem :label="t('邮箱编码或名称')" :label-width="116" :content-width="220">
        <n-input v-model:value="filters.keyword" clearable :maxlength="100" />
      </MeQueryItem>
      <MeQueryItem :label="t('状态')" :label-width="50" :content-width="120">
        <n-select v-model:value="filters.enable" :options="enableOptions()" clearable />
      </MeQueryItem>
    </MeCrud>
    <AccountEditor v-if="editor" :initial="editor.row" @close="editor = null" @saved="saved" />
  </div>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { MailAccount } from './api'
import { computed, h, onMounted, ref } from 'vue'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import MeQueryItem from '@/components/me/crud/QueryItem.vue'
import { t } from '@/i18n'
import AccountEditor from './AccountEditor.vue'
import api from './api'
import { enableOptions, enableTag, listFilters, providerLabels } from './presentation'

const table = ref<InstanceType<typeof MeCrud> | null>(null)
const filters = ref(listFilters())
const listFailed = ref(false)
const editor = ref<{ row: MailAccount | null } | null>(null)
const columns = computed<DataTableColumns<MailAccount>>(() => [
  {
    title: t('编码'),
    key: 'code',
    minWidth: 130,
    ellipsis: { tooltip: true },
  },
  {
    title: t('名称'),
    key: 'name',
    minWidth: 145,
    ellipsis: { tooltip: true },
  },
  {
    title: t('邮箱地址'),
    key: 'mailboxAddress',
    minWidth: 200,
    ellipsis: { tooltip: true },
  },
  {
    title: t('接入方式'),
    key: 'provider',
    width: 142,
    render: row => providerLabels[row.provider],
  },
  {
    title: t('凭据'),
    key: 'credentialConfigured',
    width: 112,
    render: row => t(row.credentialConfigured ? '已配置' : '未配置'),
  },
  {
    title: t('状态'),
    key: 'enable',
    width: 90,
    render: row => enableTag(row.enable),
  },
  {
    title: t('操作'),
    key: 'actions',
    width: 105,
    align: 'right',
    fixed: 'right',
    render: row => h(TableActions, {
      actions: [
        {
          key: 'edit',
          label: t('编辑'),
          onClick: () => open(row),
        },
      ],
    }),
  },
])

function open(row: MailAccount | null = null) {
  editor.value = { row }
}

function saved() {
  editor.value = null
  window.$message.success(t('操作成功'))
  void table.value?.handleSearch(true)
}

onMounted(() => table.value?.handleSearch())
defineExpose({ open })
</script>
