<template>
  <div>
    <div v-if="canManage" class="mb-12 flex justify-end">
      <n-button type="primary" :disabled="loading || failed" @click="editor = { grant: null }">
        <template #icon>
          <i class="i-material-symbols:add" aria-hidden="true" />
        </template>
        {{ t('新增授权') }}
      </n-button>
    </div>
    <n-alert v-if="failed" type="error" class="mb-12">
      {{ t('接口授权加载失败') }}
      <n-button text @click="reload">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <n-data-table
      :columns="columns"
      :data="grants"
      :loading="loading"
      :row-key="row => row.endpointId"
      :scroll-x="900"
      :size="appStore.tableSize"
    />
    <GrantEditor
      v-if="editor"
      :app-id="appId"
      :initial="editor.grant"
      :grants="grants"
      @close="editor = null"
      @saved="saved"
      @refresh="reload"
    />
  </div>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { Grant } from '@/api/open-api/api'
import { NTag } from 'naive-ui'
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue'
import api from '@/api/open-api/api'
import TableActions from '@/components/common/TableActions.vue'
import { t } from '@/i18n'
import { useAppStore } from '@/store'
import GrantEditor from './GrantEditor.vue'
import { endpointStatusLabel, endpointStatusType, isConflict } from './model'

const props = defineProps<{ appId: number, canManage: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const appStore = useAppStore()
const editor = ref<{ grant: Grant | null } | null>(null)
const grants = ref<Grant[]>([])
const loading = ref(false)
const failed = ref(false)
const revoking = ref<number | null>(null)
let request: AbortController | undefined

const columns = computed<DataTableColumns<Grant>>(() => [
  {
    title: t('接口名称'),
    key: 'displayName',
    minWidth: 140,
    ellipsis: { tooltip: true },
  },
  {
    title: 'Scope',
    key: 'endpointCode',
    width: 240,
    ellipsis: { tooltip: true },
  },
  {
    title: t('接口状态'),
    key: 'endpointStatus',
    width: 92,
    render: row => h(NTag, { size: 'small', type: endpointStatusType[row.endpointStatus] }, () => endpointStatusLabel(row.endpointStatus)),
  },
  {
    title: t('授权状态'),
    key: 'status',
    width: 92,
    render: row => h(NTag, {
      size: 'small',
      type: row.status === 'GRANTED'
        ? 'success'
        : 'default',
    }, () => t(row.status === 'GRANTED'
      ? '已授权'
      : '已撤销')),
  },
  {
    title: t('接口配额'),
    key: 'rateLimitCount',
    minWidth: 125,
    ellipsis: { tooltip: true },
    render: row => row.rateLimitCount === null
      ? t('继承应用配额')
      : t('{0} 次 / {1} 秒', row.rateLimitCount, row.rateLimitWindowSeconds),
  },
  ...(props.canManage
    ? [
        {
          title: t('操作'),
          key: 'actions',
          width: 188,
          fixed: 'right' as const,
          align: 'right' as const,
          render: (row: Grant) => h(TableActions, {
            actions: actionsFor(row),
          }),
        },
      ]
    : []),
])

function actionsFor(grant: Grant) {
  const actions: InstanceType<typeof TableActions>['$props']['actions'][number][] = []
  if (grant.endpointStatus === 'PUBLISHED') {
    actions.push({
      key: 'edit',
      label: t(grant.status === 'GRANTED'
        ? '编辑'
        : '重新授权'),
      type: grant.status === 'GRANTED'
        ? 'default'
        : 'primary',
      onClick: () => editor.value = { grant },
    })
  }
  if (grant.status === 'GRANTED') {
    actions.push({
      key: 'revoke',
      label: t('撤销授权'),
      type: 'error',
      loading: revoking.value === grant.endpointId,
      onClick: () => revoke(grant),
    })
  }
  return actions
}

async function reload() {
  request?.abort()
  const current = new AbortController()
  request = current
  loading.value = true
  failed.value = false
  try {
    const { data } = await api.grants(props.appId, current.signal)
    if (!current.signal.aborted)
      grants.value = data
  }
  catch {
    if (!current.signal.aborted)
      failed.value = true
  }
  finally {
    if (!current.signal.aborted)
      loading.value = false
  }
}

function saved() {
  editor.value = null
  window.$message.success(t('操作成功'))
  emit('changed')
  void reload()
}

function revoke(grant: Grant) {
  window.$dialog.warning({
    title: t('撤销接口授权'),
    content: t('撤销「{0}」后，该应用的现有令牌也无法再调用此接口。', grant.displayName),
    positiveText: t('撤销授权'),
    negativeText: t('取消'),
    onPositiveClick: async () => {
      if (revoking.value !== null)
        return false
      revoking.value = grant.endpointId
      try {
        await api.saveGrant(props.appId, grant.endpointId, { status: 'REVOKED', version: grant.version })
        saved()
        return true
      }
      catch (error) {
        if (isConflict(error)) {
          emit('changed')
          void reload()
          return true
        }
        return false
      }
      finally {
        revoking.value = null
      }
    },
  })
}

onMounted(reload)
onBeforeUnmount(() => request?.abort())
</script>
