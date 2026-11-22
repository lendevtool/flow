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
      :scroll-x="1280"
      :get-data="loadEndpoints"
      class="min-h-0 flex-1"
      @update:query-failed="failed = $event"
    >
      <MeQueryItem :label="t('关键字')" :label-width="56" :content-width="210">
        <n-input v-model:value="filters.keyword" :placeholder="t('接口名称、Scope 或路径')" clearable :maxlength="160" />
      </MeQueryItem>
      <MeQueryItem :label="t('模块')" :label-width="44" :content-width="145">
        <n-select v-model:value="filters.moduleCode" :options="modules" clearable />
      </MeQueryItem>
      <MeQueryItem :label="t('状态')" :label-width="44" :content-width="125">
        <n-select v-model:value="filters.status" :options="statusOptions" clearable />
      </MeQueryItem>
      <template v-if="canPublish" #toolbar>
        <div class="mb-12 flex justify-end">
          <n-button type="primary" @click="importing = true">
            <template #icon>
              <i class="i-material-symbols:add" aria-hidden="true" />
            </template>
            {{ t('从现有接口导入') }}
          </n-button>
        </div>
      </template>
    </MeCrud>
    <EndpointImport v-if="canPublish && importing" @close="importing = false" @saved="endpointImported" />
    <EndpointDetail
      v-if="selected"
      :key="selected.endpoint.id"
      :initial="selected.endpoint"
      :can-publish="canPublish"
      :downloading="downloading"
      :inspect-impact="selected.inspectImpact"
      @close="selected = null"
      @changed="reload"
      @download="emit('download', $event)"
    />
  </div>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { DocumentFormat } from './documentDownload'
import type { Endpoint, EndpointStatus, PageQuery } from '@/api/open-api/api'
import { NTag } from 'naive-ui'
import { computed, h, onMounted, ref } from 'vue'
import api from '@/api/open-api/api'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import MeQueryItem from '@/components/me/crud/QueryItem.vue'
import { t } from '@/i18n'
import EndpointDetail from './EndpointDetail.vue'
import EndpointImport from './EndpointImport.vue'
import { endpointModuleLabel, endpointModuleNames, endpointStatuses, endpointStatusLabel, endpointStatusType } from './model'

const props = defineProps<{ canPublish: boolean, downloading: boolean }>()
const emit = defineEmits<{ download: [format: DocumentFormat] }>()
interface Filters {
  keyword: string | null
  moduleCode: string | null
  status: EndpointStatus | null
}
const table = ref<InstanceType<typeof MeCrud>>()
const failed = ref(false)
const filters = ref<Filters>({ keyword: null, moduleCode: null, status: null })
const selected = ref<{ endpoint: Endpoint, inspectImpact: boolean } | null>(null)
const importing = ref(false)
const modules = computed(() => Object.keys(endpointModuleNames).map(value => ({
  value,
  label: endpointModuleLabel(value),
})))
const statusOptions = computed(() => endpointStatuses.map(value => ({
  value,
  label: endpointStatusLabel(value),
})))

const columns = computed<DataTableColumns<Endpoint>>(() => [
  {
    title: t('接口名称'),
    key: 'displayName',
    minWidth: 138,
    ellipsis: { tooltip: true },
  },
  {
    title: t('模块'),
    key: 'moduleCode',
    width: 110,
    ellipsis: { tooltip: true },
    render: row => endpointModuleLabel(row.moduleCode),
  },
  {
    title: 'Scope',
    key: 'endpointCode',
    width: 248,
    ellipsis: { tooltip: true },
  },
  {
    title: t('请求路径'),
    key: 'pathTemplate',
    minWidth: 270,
    ellipsis: { tooltip: true },
    render: row => `${row.httpMethod} ${row.pathTemplate}`,
  },
  {
    title: t('状态'),
    key: 'status',
    width: 138,
    render: row => h('div', { class: 'flex flex-wrap gap-4' }, [
      h(NTag, { size: 'small', type: endpointStatusType[row.status] }, () => endpointStatusLabel(row.status)),
      row.source?.contractMatches === false
        ? h(NTag, { size: 'small', type: 'warning' }, () => t('契约已变更'))
        : null,
    ]),
  },
  {
    title: t('授权应用数'),
    key: 'authorizedAppCount',
    width: 105,
  },
  {
    title: t('操作'),
    key: 'actions',
    width: props.canPublish ? 206 : 96,
    fixed: 'right',
    align: 'right',
    render: endpoint => h(TableActions, {
      actions: actionsFor(endpoint),
    }),
  },
])

function actionsFor(endpoint: Endpoint) {
  const actions: InstanceType<typeof TableActions>['$props']['actions'][number][] = [
    {
      key: 'detail',
      label: t('详情'),
      secondary: true,
      onClick: () => selected.value = { endpoint, inspectImpact: false },
    },
  ]
  if (props.canPublish) {
    const published = endpoint.status === 'PUBLISHED'
    const changed = endpoint.source?.contractMatches === false
    const offline = published || (changed && endpoint.status === 'REGISTERED')
    actions.push({
      key: 'status',
      label: t(offline
        ? '下线'
        : changed ? '重新确认契约' : '发布'),
      type: offline ? 'error' : 'primary',
      onClick: () => selected.value = { endpoint, inspectImpact: published },
    })
  }
  return actions
}

function loadEndpoints({ keyword, moduleCode, status, ...page }: Filters & PageQuery) {
  return api.endpoints({
    ...page,
    keyword: keyword?.trim() || undefined,
    moduleCode: moduleCode ?? undefined,
    status: status ?? undefined,
  })
}

function reload() {
  return table.value?.handleSearch(true)
}

function endpointImported(endpoint: Endpoint) {
  importing.value = false
  selected.value = { endpoint, inspectImpact: false }
  void reload()
  window.$message.success(t('操作成功'))
}

onMounted(reload)
</script>
