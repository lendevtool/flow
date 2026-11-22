<template>
  <div class="h-full flex flex-col">
    <n-alert v-if="listFailed || optionsFailed" type="error" class="mb-12 shrink-0">
      {{ t('加载失败') }}
      <n-button text @click="reload">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <MeCrud
      ref="table"
      v-model:query-items="filters"
      :columns="columns"
      :get-data="api.templates"
      :page-size="20"
      :scroll-x="canSend ? 1040 : 935"
      class="min-h-0 flex-1"
      @update:query-failed="listFailed = $event"
    >
      <MeQueryItem :label="t('模板编码或名称')" :label-width="116" :content-width="220">
        <n-input v-model:value="filters.keyword" clearable :maxlength="100" />
      </MeQueryItem>
      <MeQueryItem :label="t('状态')" :label-width="50" :content-width="120">
        <n-select v-model:value="filters.enable" :options="enableOptions()" clearable />
      </MeQueryItem>
    </MeCrud>
    <TemplateEditor
      v-if="editor"
      :initial="editor.row"
      :accounts="accounts"
      :accounts-loading="optionsLoading"
      :accounts-failed="optionsFailed"
      @retry-accounts="loadAccounts"
      @close="editor = null"
      @saved="saved"
    />
    <TestMailDrawer v-if="testTemplate && canSend" :template="testTemplate" @close="testTemplate = null" />
  </div>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { MailOption, MailTemplate } from './api'
import { computed, h, onMounted, ref } from 'vue'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import MeQueryItem from '@/components/me/crud/QueryItem.vue'
import { t } from '@/i18n'
import api from './api'
import { enableOptions, enableTag, listFilters, sendModeLabels } from './presentation'
import TemplateEditor from './TemplateEditor.vue'
import TestMailDrawer from './TestMailDrawer.vue'

const props = defineProps<{ canSend: boolean }>()
const table = ref<InstanceType<typeof MeCrud> | null>(null)
const filters = ref(listFilters())
const listFailed = ref(false)
const optionsFailed = ref(false)
const optionsLoading = ref(false)
const accounts = ref<MailOption[]>([])
const editor = ref<{ row: MailTemplate | null } | null>(null)
const testTemplate = ref<MailTemplate | null>(null)
const accountsById = computed(() => new Map(accounts.value.map(account => [account.id, account])))
const columns = computed<DataTableColumns<MailTemplate>>(() => [
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
    title: t('发件邮箱'),
    key: 'accountId',
    minWidth: 170,
    ellipsis: { tooltip: true },
    render: row => accountsById.value.get(row.accountId)?.name ?? String(row.accountId),
  },
  {
    title: t('发送方式'),
    key: 'sendMode',
    width: 120,
    render: row => t(sendModeLabels[row.sendMode]),
  },
  {
    title: t('版本'),
    key: 'revision',
    width: 80,
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
    width: props.canSend ? 210 : 105,
    align: 'right',
    fixed: 'right',
    render: row => h(TableActions, {
      actions: [
        {
          key: 'edit',
          label: t('编辑'),
          onClick: () => open(row),
        },
        ...(props.canSend
          ? [
              {
                key: 'test-send',
                label: t('测试发送'),
                disabled: !row.enable || accountsById.value.get(row.accountId)?.enable === false,
                onClick: () => testTemplate.value = row,
              },
            ]
          : []),
      ],
    }),
  },
])

async function loadAccounts() {
  if (optionsLoading.value)
    return
  optionsFailed.value = false
  optionsLoading.value = true
  try {
    accounts.value = (await api.accountOptions()).data
  }
  catch {
    optionsFailed.value = true
  }
  finally {
    optionsLoading.value = false
  }
}

function reload() {
  void loadAccounts()
  void table.value?.handleSearch()
}

function open(row: MailTemplate | null = null) {
  editor.value = { row }
  void loadAccounts()
}

function saved() {
  editor.value = null
  window.$message.success(t('操作成功'))
  void table.value?.handleSearch(true)
}

onMounted(reload)
defineExpose({ open })
</script>
