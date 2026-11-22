<template>
  <n-drawer :show="true" width="min(840px, 100vw)" :aria-label="t('机构详情')" @update:show="!$event && emit('close')">
    <n-drawer-content :title="t('机构详情')" closable>
      <n-alert v-if="detailFailed" type="error" class="mb-12">
        {{ t('加载详情失败') }}
        <NButton text @click="loadDetail">
          {{ t('重试') }}
        </NButton>
      </n-alert>
      <n-spin :show="detailLoading" class="min-h-80">
        <template v-if="record">
          <div class="mb-16 flex flex-wrap items-center justify-between gap-12">
            <h2 class="break-all text-lg font-semibold">
              {{ displayName(record.nameZh, record.nameEn) }}
            </h2>
            <n-space v-if="canEdit || canSubmit">
              <NButton v-if="canEdit" :disabled="detailFailed || detailLoading" @click="emit('edit', record)">
                {{ t('编辑') }}
              </NButton>
              <NButton v-if="canSubmit" type="primary" :disabled="!!submitBlockedReason || detailFailed || detailLoading" @click="emit('submit', record)">
                {{ t('发起变更申请') }}
              </NButton>
            </n-space>
          </div>
          <n-alert v-if="canSubmit && submitBlockedReason" type="info" class="mb-16">
            {{ submitBlockedReason }}
          </n-alert>

          <n-card v-if="focusedChangeId !== null" class="mb-16" size="small">
            <template #header>
              <h3 ref="snapshotHeading" tabindex="-1">
                {{ t('送审原值') }}
              </h3>
            </template>
            <template #header-extra>
              <NButton text @click="emit('focus', null)">
                {{ t('关闭') }}
              </NButton>
            </template>
            <n-alert v-if="snapshotFailed" type="error">
              {{ t('加载变更申请失败') }}
              <NButton text @click="loadSnapshot">
                {{ t('重试') }}
              </NButton>
            </n-alert>
            <n-spin v-else :show="snapshotLoading" class="min-h-40">
              <template v-if="snapshot">
                <div class="mb-12 flex flex-wrap items-center gap-12">
                  <RouterLink :to="workflowLink(snapshot)">
                    WF-{{ snapshot.flowInstanceId }}
                  </RouterLink>
                  <NTag size="small" :type="changeStatus[snapshot.status].type">
                    {{ t(changeStatus[snapshot.status].label) }}
                  </NTag>
                </div>
                <n-descriptions bordered :column="1" size="small" class="institution-properties">
                  <n-descriptions-item v-for="field in snapshotFields" :key="field.label" :label="field.label">
                    {{ field.value }}
                  </n-descriptions-item>
                </n-descriptions>
              </template>
            </n-spin>
          </n-card>

          <n-descriptions :column="2" label-placement="top" bordered size="small" class="institution-properties">
            <n-descriptions-item v-for="field in detailFields" :key="field.label" :label="field.label" :span="field.span">
              {{ field.value }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('状态')">
              <NTag :type="record.enabled ? 'success' : 'default'" size="small">
                {{ t(record.enabled ? '启用' : '停用') }}
              </NTag>
            </n-descriptions-item>
            <n-descriptions-item :label="t('创建人')">
              <PersonLink :user-id="record.createdBy" :label="`#${record.createdBy}`" />
            </n-descriptions-item>
            <n-descriptions-item :label="t('创建时间')">
              {{ formatDateTime(record.createdAt) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('更新人')">
              <PersonLink :user-id="record.updatedBy" :label="`#${record.updatedBy}`" />
            </n-descriptions-item>
            <n-descriptions-item :label="t('更新时间')">
              {{ formatDateTime(record.updatedAt) }}
            </n-descriptions-item>
          </n-descriptions>

          <h3 class="mb-12 mt-24 text-base font-semibold">
            {{ t('变更历史') }}
          </h3>
          <n-alert v-if="historyFailed" type="error" class="mb-12">
            {{ t('加载失败') }}
            <NButton text @click="loadHistory(historyPage)">
              {{ t('重试') }}
            </NButton>
          </n-alert>
          <n-data-table
            size="small"
            :columns="columns"
            :data="history"
            :loading="historyLoading"
            :row-key="row => row.id"
            :scroll-x="585"
          >
            <template #empty>
              <n-empty v-if="!historyFailed && !historyLoading" :description="t('暂无变更申请')" />
            </template>
          </n-data-table>
          <n-pagination
            v-if="historyTotal > pageSize"
            class="mt-12 justify-end"
            :page="historyPage"
            :page-size="pageSize"
            :item-count="historyTotal"
            :page-slot="5"
            @update:page="loadHistory"
          />
        </template>
      </n-spin>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { Institution, InstitutionChange, InstitutionOptions } from './api'
import { NButton, NTag } from 'naive-ui'
import { computed, h, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import PersonLink from '@/components/common/PersonLink.vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import { formatDateTime } from '@/utils'
import api from './api'
import { approvalFields, approvalValueLabel, changeStatus, fieldLabel, institutionEnums, institutionOptionName, institutionTexts } from './model'

const props = defineProps<{
  id: number
  focusedChangeId: number | null
  refreshToken: number
  options: InstitutionOptions
  canEdit: boolean
  canSubmit: boolean
  submitBlockedReason: string | null
}>()
const emit = defineEmits<{
  close: []
  edit: [record: Institution]
  submit: [record: Institution]
  focus: [id: number | null]
}>()

const pageSize = 20
const record = ref<Institution | null>(null)
const detailLoading = ref(false)
const detailFailed = ref(false)
const history = ref<InstitutionChange[]>([])
const historyPage = ref(1)
const historyTotal = ref(0)
const historyLoading = ref(false)
const historyFailed = ref(false)
const snapshotHeading = ref<HTMLElement>()
const snapshot = ref<InstitutionChange | null>(null)
const snapshotLoading = ref(false)
const snapshotFailed = ref(false)
let detailRequest: AbortController | undefined
let historyRequest: AbortController | undefined
let snapshotRequest: AbortController | undefined

const detailFields = computed(() => {
  const current = record.value
  if (!current)
    return []
  const textFields = institutionTexts.map(field => ({
    label: fieldLabel(props.options, field),
    value: current[field.key] ?? '—',
    span: field.key === 'remark'
      ? 2
      : 1,
  }))
  const enumFields = institutionEnums.map(field => ({
    label: fieldLabel(props.options, field),
    value: institutionOptionName(props.options[field.options], current[field.key]),
    span: 1,
  }))
  return [
    ...textFields.slice(0, 3),
    ...enumFields,
    ...textFields.slice(3),
  ]
})

const snapshotFields = computed(() => {
  const values = snapshot.value?.baseValues ?? {}
  return approvalFields.filter(field => Object.hasOwn(values, field.code)).map(field => ({
    label: fieldLabel(props.options, field),
    value: approvalValueLabel(props.options, field, values[field.code]),
  }))
})

function workflowLink(change: InstitutionChange) {
  return {
    name: 'WorkflowTask',
    params: { id: change.flowInstanceId },
  }
}

const columns = computed<DataTableColumns<InstitutionChange>>(() => [
  {
    title: t('流程编号'),
    key: 'flowInstanceId',
    width: 125,
    ellipsis: { tooltip: true },
    render: row => h(RouterLink, {
      to: workflowLink(row),
    }, () => `WF-${row.flowInstanceId}`),
  },
  {
    title: t('生效结果'),
    key: 'status',
    width: 125,
    render: row => h(NTag, { size: 'small', type: changeStatus[row.status].type }, () => t(changeStatus[row.status].label)),
  },
  {
    title: t('处理时间'),
    key: 'resolvedAt',
    width: 175,
    render: row => row.resolvedAt
      ? formatDateTime(row.resolvedAt)
      : '—',
  },
  {
    title: t('操作'),
    key: 'actions',
    width: 160,
    align: 'right',
    render: row => h(NButton, {
      size: 'small',
      disabled: row.id === props.focusedChangeId,
      onClick: () => emit('focus', row.id),
    }, () => t('查看送审原值')),
  },
])

async function loadDetail() {
  detailRequest?.abort()
  const controller = new AbortController()
  detailRequest = controller
  detailLoading.value = true
  detailFailed.value = false
  try {
    const { data } = await api.detail(props.id, controller.signal)
    if (!controller.signal.aborted)
      record.value = data
  }
  catch {
    if (!controller.signal.aborted)
      detailFailed.value = true
  }
  finally {
    if (!controller.signal.aborted)
      detailLoading.value = false
  }
}

async function loadHistory(page: number) {
  historyRequest?.abort()
  const controller = new AbortController()
  historyRequest = controller
  historyPage.value = page
  historyLoading.value = true
  historyFailed.value = false
  try {
    const { data } = await api.changes(props.id, { pageNo: page, pageSize }, controller.signal)
    if (!controller.signal.aborted) {
      const lastPage = Math.max(1, Math.ceil(data.total / pageSize))
      if (page > lastPage) {
        void loadHistory(lastPage)
        return
      }
      history.value = data.pageData
      historyTotal.value = data.total
    }
  }
  catch {
    if (!controller.signal.aborted) {
      history.value = []
      historyFailed.value = true
    }
  }
  finally {
    if (!controller.signal.aborted)
      historyLoading.value = false
  }
}

async function loadSnapshot() {
  snapshotRequest?.abort()
  snapshot.value = null
  snapshotFailed.value = false
  const changeId = props.focusedChangeId
  if (changeId === null) {
    snapshotLoading.value = false
    return
  }
  const controller = new AbortController()
  snapshotRequest = controller
  snapshotLoading.value = true
  try {
    const { data } = await api.changeDetail(props.id, changeId, controller.signal)
    if (!controller.signal.aborted)
      snapshot.value = data
  }
  catch {
    if (!controller.signal.aborted)
      snapshotFailed.value = true
  }
  finally {
    if (!controller.signal.aborted)
      snapshotLoading.value = false
  }
}

watch(() => props.refreshToken, () => {
  void loadDetail()
  void loadHistory(1)
}, { immediate: true })
watch(() => [props.focusedChangeId, props.refreshToken], loadSnapshot, { immediate: true })
watch([
  snapshotHeading,
  () => props.focusedChangeId,
], ([heading]) => heading?.focus(), { flush: 'post' })
onBeforeUnmount(() => {
  detailRequest?.abort()
  historyRequest?.abort()
  snapshotRequest?.abort()
})
</script>

<style scoped>
.institution-properties {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
</style>
