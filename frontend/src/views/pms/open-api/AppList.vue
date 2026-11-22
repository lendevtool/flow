<template>
  <div class="h-full flex flex-col">
    <n-alert v-if="failed" type="error" class="mb-12 shrink-0">
      {{ t('加载失败') }}
      <n-button text @click="reload">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <MeCrud
      ref="table"
      v-model:query-items="filters"
      :columns="columns"
      :page-size="20"
      :scroll-x="1110"
      :get-data="loadApps"
      class="min-h-0 flex-1"
      @update:query-failed="failed = $event"
    >
      <MeQueryItem :label="t('应用名称或 AppID')" :label-width="120" :content-width="225">
        <n-input v-model:value="filters.keyword" clearable :maxlength="128" />
      </MeQueryItem>
      <MeQueryItem :label="t('环境')" :label-width="48" :content-width="115">
        <n-input v-model:value="filters.environment" clearable :maxlength="32" />
      </MeQueryItem>
      <MeQueryItem :label="t('状态')" :label-width="48" :content-width="110">
        <n-select v-model:value="filters.enabled" :options="statusOptions" clearable />
      </MeQueryItem>
    </MeCrud>
  </div>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { App, PageQuery } from '@/api/open-api/api'
import { NTag } from 'naive-ui'
import { computed, h, onMounted, ref } from 'vue'
import api from '@/api/open-api/api'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import MeQueryItem from '@/components/me/crud/QueryItem.vue'
import { t } from '@/i18n'

const props = defineProps<{ canManage: boolean }>()
const emit = defineEmits<{ select: [app: App], edit: [app: App], changeStatus: [app: App] }>()
interface Filters {
  keyword: string | null
  environment: string | null
  enabled: 'enabled' | 'disabled' | null
}
const table = ref<InstanceType<typeof MeCrud>>()
const failed = ref(false)
const filters = ref<Filters>({ keyword: null, environment: null, enabled: null })
const statusOptions = computed(() => [
  {
    label: t('启用'),
    value: 'enabled',
  },
  {
    label: t('停用'),
    value: 'disabled',
  },
])

const columns = computed<DataTableColumns<App>>(() => [
  {
    title: t('应用名称'),
    key: 'name',
    minWidth: 180,
    ellipsis: { tooltip: true },
  },
  {
    title: 'AppID',
    key: 'clientId',
    width: 240,
    ellipsis: { tooltip: true },
  },
  {
    title: t('环境'),
    key: 'environment',
    width: 105,
    ellipsis: { tooltip: true },
  },
  {
    title: t('负责人'),
    key: 'ownerDisplayName',
    minWidth: 128,
    ellipsis: { tooltip: true },
    render: row => row.ownerDisplayName || '—',
  },
  {
    title: t('状态'),
    key: 'enabled',
    width: 90,
    render: row => h(NTag, {
      size: 'small',
      type: row.enabled ? 'success' : 'default',
    }, () => t(row.enabled ? '启用' : '停用')),
  },
  {
    title: t('操作'),
    key: 'actions',
    width: props.canManage ? 235 : 96,
    fixed: 'right',
    align: 'right',
    render: row => h(TableActions, {
      actions: actionsFor(row),
    }),
  },
])

function actionsFor(app: App) {
  const actions: InstanceType<typeof TableActions>['$props']['actions'][number][] = [
    {
      key: 'detail',
      label: t('详情'),
      secondary: true,
      onClick: () => emit('select', app),
    },
  ]
  if (props.canManage) {
    actions.push(
      {
        key: 'edit',
        label: t('编辑'),
        onClick: () => emit('edit', app),
      },
      {
        key: 'status',
        label: t(app.enabled ? '停用' : '启用'),
        type: app.enabled ? 'error' : 'primary',
        onClick: () => emit('changeStatus', app),
      },
    )
  }
  return actions
}

function loadApps({ keyword, environment, enabled, ...page }: Filters & PageQuery) {
  return api.apps({
    ...page,
    keyword: keyword?.trim() || undefined,
    environment: environment?.trim() || undefined,
    enabled: enabled === null
      ? undefined
      : enabled === 'enabled',
  })
}

function reload() {
  return table.value?.handleSearch(true)
}

onMounted(reload)
defineExpose({ reload })
</script>
