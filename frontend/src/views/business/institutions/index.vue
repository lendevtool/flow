<template>
  <CommonPage>
    <template #action>
      <NButton v-if="can('InstitutionCreate')" type="primary" :disabled="!canCreateRecord" @click="editor = { initial: null }">
        <template #icon>
          <i class="i-material-symbols:add" aria-hidden="true" />
        </template>
        {{ t('新增机构') }}
      </NButton>
    </template>

    <div ref="tableContainer" class="h-full flex flex-col">
      <n-alert v-if="optionsFailed" type="error" class="mb-12 shrink-0">
        {{ t('机构选项加载失败') }}
        <NButton text @click="loadOptions">
          {{ t('重试') }}
        </NButton>
      </n-alert>
      <n-alert v-else-if="optionsReady && can('InstitutionCreate') && !canCreateRecord" type="warning" class="mb-12 shrink-0">
        {{ t('请在参数管理中启用至少一个机构类型及一个注册国家或地区。') }}
      </n-alert>
      <n-alert v-if="listFailed" type="error" class="mb-12 shrink-0">
        {{ t('加载失败') }}
        <NButton text @click="table?.handleSearch(true)">
          {{ t('重试') }}
        </NButton>
      </n-alert>

      <MeCrud
        ref="table"
        v-model:query-items="queryItems"
        class="min-h-0 flex-1"
        :page-size="20"
        :scroll-x="scrollWidth"
        :columns="columns"
        :get-data="loadInstitutions"
        @update:query-failed="listFailed = $event"
      >
        <MeQueryItem :label="t('机构编码或名称')" :label-width="112" :content-width="210">
          <n-input v-model:value="queryItems.keyword" clearable :maxlength="200" :input-props="{ 'aria-label': t('机构编码或名称') }" />
        </MeQueryItem>
        <MeQueryItem :label="t('机构类型')" :label-width="100" :content-width="158">
          <n-select v-model:value="queryItems.typeItemId" :aria-label="t('机构类型')" :options="typeOptions" :loading="optionsLoading" filterable clearable />
        </MeQueryItem>
        <MeQueryItem :label="t('注册国家或地区')" :label-width="114" :content-width="175">
          <n-select v-model:value="queryItems.countryRegionItemId" :aria-label="t('注册国家或地区')" :options="countryOptions" :loading="optionsLoading" filterable clearable />
        </MeQueryItem>
        <MeQueryItem :label="t('状态')" :label-width="55" :content-width="100">
          <n-select v-model:value="queryItems.enabled" :aria-label="t('状态')" :options="statusOptions" clearable />
        </MeQueryItem>
        <template #empty="{ loading }">
          <n-empty v-if="!loading && !listFailed" :description="t('暂无机构')" />
        </template>
      </MeCrud>
    </div>

    <InstitutionDetails
      v-if="selectedId !== null"
      :id="selectedId"
      :key="selectedId"
      :focused-change-id="selectedChangeId"
      :refresh-token="detailsRefresh"
      :options="options"
      :can-edit="can('InstitutionEdit') && optionsReady"
      :can-submit="can('InstitutionSubmit') && optionsReady"
      :submit-blocked-reason="submitBlockedReason"
      @close="closeDetails"
      @edit="record => editor = { initial: record }"
      @submit="application = $event"
      @focus="focusChange"
    />
    <InstitutionEditor v-if="editor" :initial="editor.initial" :options="options" @close="editor = null" @saved="saved" />
    <InstitutionChangeEditor v-if="application" :record="application" :options="options" @close="application = null" @submitted="submitted" />
  </CommonPage>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { Institution, InstitutionChange, InstitutionOptions, Paging } from './api'
import { useElementSize } from '@vueuse/core'
import { NButton, NTag } from 'naive-ui'
import { computed, h, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CommonPage from '@/components/common/CommonPage.vue'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import MeQueryItem from '@/components/me/crud/QueryItem.vue'
import { t } from '@/i18n'
import { useAppStore, useUserStore } from '@/store'
import { formatDateTime } from '@/utils'
import api from './api'
import InstitutionChangeEditor from './InstitutionChangeEditor.vue'
import InstitutionDetails from './InstitutionDetails.vue'
import InstitutionEditor from './InstitutionEditor.vue'
import { approvalSelectOptions, availableApprovalFields, institutionOptionName, institutionSelectOptions, routeId } from './model'

defineOptions({ name: 'InstitutionView' })

type Permission = 'InstitutionCreate' | 'InstitutionEdit' | 'InstitutionStatus' | 'InstitutionSubmit'
interface Filters {
  keyword: string | null
  typeItemId: number | null
  countryRegionItemId: number | null
  enabled: 1 | 0 | null
}

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const app = useAppStore()
const tableContainer = ref<HTMLElement>()
const { width: tableWidth } = useElementSize(tableContainer)
const table = ref<InstanceType<typeof MeCrud> | null>(null)
const queryItems = ref<Filters>({
  keyword: null,
  typeItemId: null,
  countryRegionItemId: null,
  enabled: null,
})
const options = ref<InstitutionOptions>({ types: [], countries: [] })
const optionsReady = ref(false)
const optionsLoading = ref(false)
const optionsFailed = ref(false)
const listFailed = ref(false)
const editor = ref<{ initial: Institution | null } | null>(null)
const application = ref<Institution | null>(null)
const detailsRefresh = ref(0)
const statusSaving = ref<number | null>(null)
const submitBlockedReason = computed(() => {
  const current = options.value
  if (current.approvalOptions === null)
    return t('请先发布机构资料变更流程模板。')
  const canPropose = availableApprovalFields(current).some(field =>
    !('options' in field) || approvalSelectOptions(current[field.options], current.approvalOptions?.[field.options]).length > 0,
  )
  return canPropose
    ? null
    : t('当前流程模板没有可用的机构变更字段。')
})

// 页面按完整路由缓存，列表内的抽屉操作使用本地状态以保留筛选和分页。
const sourceId = routeId(route.query.id)
const selectedId = ref(sourceId)
const selectedChangeId = ref(sourceId === null
  ? null
  : routeId(route.query.changeId))
const canCreateRecord = computed(() => optionsReady.value && options.value.types.some(option => option.enabled) && options.value.countries.some(option => option.enabled))
const typeOptions = computed(() => institutionSelectOptions(options.value.types))
const countryOptions = computed(() => institutionSelectOptions(options.value.countries))
const statusOptions = computed(() => [
  {
    label: t('启用'),
    value: 1,
  },
  {
    label: t('停用'),
    value: 0,
  },
])
const useActionsDropdown = computed(() => tableWidth.value > 0 && tableWidth.value < 680)

function can(code: Permission) {
  const buttons = route.meta.btns
  return user.currentRole.code === 'SUPER_ADMIN'
    || (Array.isArray(buttons) && buttons.some(button => button.code === code))
}

const columns = computed<DataTableColumns<Institution>>(() => [
  {
    title: t('机构编码'),
    key: 'code',
    width: 155,
    ellipsis: { tooltip: true },
  },
  {
    title: t('中文全称'),
    key: 'nameZh',
    width: 190,
    ellipsis: { tooltip: true },
  },
  {
    title: t('英文全称'),
    key: 'nameEn',
    width: 190,
    ellipsis: { tooltip: true },
    render: row => row.nameEn ?? '—',
  },
  {
    title: t('机构类型'),
    key: 'typeItemId',
    width: 135,
    ellipsis: { tooltip: true },
    render: row => institutionOptionName(options.value.types, row.typeItemId),
  },
  {
    title: t('注册国家或地区'),
    key: 'countryRegionItemId',
    width: 150,
    ellipsis: { tooltip: true },
    render: row => institutionOptionName(options.value.countries, row.countryRegionItemId),
  },
  {
    title: t('状态'),
    key: 'enabled',
    width: 95,
    render: row => h(NTag, {
      size: 'small',
      type: row.enabled ? 'success' : 'default',
    }, () => t(row.enabled ? '启用' : '停用')),
  },
  {
    title: t('更新时间'),
    key: 'updatedAt',
    width: 175,
    render: row => formatDateTime(row.updatedAt),
  },
  {
    title: t('操作'),
    key: 'actions',
    width: useActionsDropdown.value || (!can('InstitutionEdit') && !can('InstitutionStatus'))
      ? 94
      : 300,
    fixed: 'right',
    align: 'right',
    render: row => h(TableActions, {
      actions: actionsFor(row),
      size: app.tableSize === 'small'
        ? 'tiny'
        : 'small',
      dropdown: useActionsDropdown.value,
    }),
  },
])
const scrollWidth = computed(() => columns.value.reduce((sum, column) => sum + (Number(column.width) || 0), 0))

function actionsFor(row: Institution) {
  const actions: InstanceType<typeof TableActions>['$props']['actions'][number][] = [
    {
      key: 'details',
      label: t('详情'),
      icon: 'i-fe:eye text-14',
      secondary: true,
      onClick: () => openDetails(row.id),
    },
  ]
  if (can('InstitutionEdit')) {
    actions.push({
      key: 'edit',
      label: t('编辑'),
      icon: 'i-fe:edit-2 text-14',
      disabled: !optionsReady.value,
      onClick: () => editor.value = { initial: row },
    })
  }
  if (can('InstitutionStatus')) {
    actions.push({
      key: 'status',
      label: t(row.enabled ? '停用' : '启用'),
      icon: row.enabled ? 'i-fe:slash text-14' : 'i-fe:check-circle text-14',
      type: row.enabled ? 'error' : 'primary',
      secondary: true,
      disabled: statusSaving.value !== null,
      loading: statusSaving.value === row.id,
      onClick: () => confirmStatus(row),
    })
  }
  return actions
}

function loadInstitutions({ pageNo, pageSize, keyword, typeItemId, countryRegionItemId, enabled }: Filters & Paging) {
  return api.page({
    pageNo,
    pageSize,
    keyword: keyword?.trim() || undefined,
    typeItemId: typeItemId ?? undefined,
    countryRegionItemId: countryRegionItemId ?? undefined,
    enabled: enabled === null
      ? undefined
      : enabled === 1,
  })
}

async function loadOptions() {
  if (optionsLoading.value)
    return
  optionsLoading.value = true
  optionsFailed.value = false
  try {
    options.value = (await api.options()).data
    optionsReady.value = true
  }
  catch {
    optionsFailed.value = true
  }
  finally {
    optionsLoading.value = false
  }
}

function openDetails(id: number) {
  selectedId.value = id
  selectedChangeId.value = null
}

function closeDetails() {
  selectedId.value = null
  selectedChangeId.value = null
  if (sourceId !== null) {
    void router.replace({
      query: { ...route.query, id: undefined, changeId: undefined },
    })
  }
}

function focusChange(id: number | null) {
  selectedChangeId.value = id
}

function saved(created: boolean) {
  editor.value = null
  window.$message.success(t('操作成功'))
  refresh(!created)
}

function submitted(change: InstitutionChange) {
  application.value = null
  window.$message.success(t('变更申请已提交'))
  if (selectedId.value === change.institutionId) {
    detailsRefresh.value++
    focusChange(change.id)
  }
}

function refresh(keepPage: boolean) {
  void table.value?.handleSearch(keepPage)
  if (selectedId.value !== null)
    detailsRefresh.value++
}

function confirmStatus(row: Institution) {
  const enabled = !row.enabled
  const dialog = window.$dialog.warning({
    title: t(enabled ? '启用机构' : '停用机构'),
    content: t(enabled ? '确定启用机构「{0}」？' : '确定停用机构「{0}」？可按停用状态查询其档案和历史。', row.code),
    positiveText: t(enabled ? '启用' : '停用'),
    negativeText: t('取消'),
    maskClosable: false,
    closable: false,
    closeOnEsc: false,
    onNegativeClick: () => !dialog.loading,
    async onPositiveClick() {
      if (statusSaving.value !== null)
        return false
      statusSaving.value = row.id
      dialog.loading = true
      try {
        await api.status(row.id, enabled, row.version)
        window.$message.success(t('操作成功'))
        refresh(true)
      }
      catch (error) {
        if (error && typeof error === 'object' && 'code' in error && error.code === 409) {
          refresh(true)
          return true
        }
        return false
      }
      finally {
        dialog.loading = false
        statusSaving.value = null
      }
    },
  })
}

onMounted(() => {
  void loadOptions()
  void table.value?.handleSearch()
})
</script>
