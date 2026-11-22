<template>
  <CommonPage>
    <template v-if="(tab === 'groups' && canEdit) || (tab === 'scenes' && canEditWorkflowScenes)" #action>
      <n-button v-if="tab === 'groups'" type="primary" @click="openEditor()">
        <i class="i-material-symbols:add mr-4 text-18" aria-hidden="true" />
        {{ t('新建通知群组') }}
      </n-button>
      <n-button v-else type="primary" @click="sceneBindings?.openCreate()">
        <i class="i-material-symbols:add mr-4 text-18" aria-hidden="true" />
        {{ t('新增流程通知场景') }}
      </n-button>
    </template>

    <div class="h-full min-h-0 flex flex-col">
      <n-tabs v-if="canViewScenes || canViewDeliveries" v-model:value="tab" type="line" class="mb-16 shrink-0">
        <n-tab name="groups">
          {{ t('通知群组') }}
        </n-tab>
        <n-tab v-if="canViewScenes" name="scenes">
          {{ t('场景绑定') }}
        </n-tab>
        <n-tab v-if="canViewDeliveries" name="deliveries">
          {{ t('投递查询') }}
        </n-tab>
      </n-tabs>

      <div v-show="tab === 'groups'" class="min-h-0 flex flex-col flex-1">
        <n-alert v-if="listFailed" type="error" class="mb-12 shrink-0">
          {{ t('加载失败') }}
          <n-button text @click="reload">
            {{ t('重试') }}
          </n-button>
        </n-alert>
        <MeCrud
          ref="table"
          v-model:query-items="filters"
          :page-size="PAGE_SIZE"
          :columns="columns"
          :get-data="loadGroups"
          :scroll-x="canEdit ? 950 : 795"
          class="min-h-0 flex-1"
          @update:query-failed="listFailed = $event"
        >
          <MeQueryItem :label="t('群组名称')" :label-width="85" :content-width="210">
            <n-input v-model:value="filters.keyword" :maxlength="100" clearable :input-props="{ 'aria-label': t('群组名称') }" :placeholder="t('搜索群组名称')" />
          </MeQueryItem>
          <MeQueryItem :label="t('状态')" :label-width="50" :content-width="130">
            <n-select v-model:value="filters.status" :options="statusOptions" clearable />
          </MeQueryItem>
          <template #empty="{ loading }">
            <n-empty v-if="!loading && !listFailed" :description="t('没有匹配的通知群组')" />
          </template>
        </MeCrud>
      </div>

      <SceneBindings
        v-if="canViewScenes && tab === 'scenes'"
        ref="sceneBindings"
        :can-audit="canAudit"
        :can-bind="canBindScenes"
        :can-edit="canEditWorkflowScenes"
        @saved="reload"
      />
      <WorkflowDeliveries v-if="canViewDeliveries && tab === 'deliveries'" :can-view-mail-log="canViewMailLog" />
    </div>

    <GroupEditor
      v-if="editor"
      :id="editor.id"
      :key="`${editor.id ?? 'create'}-${editor.readonly}`"
      :readonly="editor.readonly"
      :can-audit="canAudit"
      @close="editor = null"
      @saved="saved"
    />
  </CommonPage>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { GroupRow, Paging } from './api'
import dayjs from 'dayjs'
import { NTag } from 'naive-ui'
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import MeQueryItem from '@/components/me/crud/QueryItem.vue'
import { t } from '@/i18n'
import { usePermissionStore, useUserStore } from '@/store'
import api from './api'
import GroupEditor from './GroupEditor.vue'
import { errorMessage, errorReason, inUseText, PAGE_SIZE } from './model'
import SceneBindings from './SceneBindings.vue'
import WorkflowDeliveries from './WorkflowDeliveries.vue'

defineOptions({ name: 'NotificationGroupView' })

interface Filters {
  keyword: string | null
  status: 'ENABLED' | 'DISABLED' | null
}

const route = useRoute()
const user = useUserStore()
const permissions = usePermissionStore()
const canEdit = computed(() => can('NotificationGroupEdit'))
const canBindScenes = computed(() => can('NotificationSceneRecipientEdit'))
const canEditWorkflowScenes = computed(() => can('WorkflowNotificationSceneEdit'))
const canViewScenes = computed(() => canBindScenes.value || canEditWorkflowScenes.value)
const canViewDeliveries = computed(() => can('WorkflowNotificationDeliveryView'))
const canAudit = computed(() => permissions.hasRoute('/pms/audit'))
const canViewMailLog = computed(() => user.currentRole.code === 'SUPER_ADMIN'
  || (permissions.hasRoute('/pms/mail') && permissions.resourcesByCode.has('MailLog')))

const tab = ref<'groups' | 'scenes' | 'deliveries'>('groups')
const table = ref<InstanceType<typeof MeCrud> | null>(null)
const sceneBindings = ref<InstanceType<typeof SceneBindings> | null>(null)
const editor = ref<{ id?: number, readonly: boolean } | null>(null)
const filters = ref<Filters>({ keyword: null, status: null })
const listFailed = ref(false)
const statusWrites = reactive(new Set<number>())
const statusOptions = computed(() => [
  {
    value: 'ENABLED',
    label: t('启用'),
  },
  {
    value: 'DISABLED',
    label: t('停用'),
  },
])

function can(code: string) {
  const buttons = route.meta.btns
  return user.currentRole.code === 'SUPER_ADMIN'
    || (Array.isArray(buttons) && buttons.some(button => button.code === code))
}

const columns = computed<DataTableColumns<GroupRow>>(() => [
  {
    title: t('群组名称'),
    key: 'name',
    minWidth: 145,
    ellipsis: { tooltip: true },
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
    title: t('指定用户数'),
    key: 'userCount',
    width: 110,
    align: 'right',
  },
  {
    title: t('部门数'),
    key: 'departmentCount',
    width: 95,
    align: 'right',
  },
  {
    title: t('引用场景数'),
    key: 'usageCount',
    width: 105,
    align: 'right',
  },
  {
    title: t('最后更新时间'),
    key: 'updatedAt',
    width: 165,
    render: row => dayjs(row.updatedAt).format('YYYY-MM-DD HH:mm:ss'),
  },
  {
    title: t('操作'),
    key: 'actions',
    width: canEdit.value ? 240 : 95,
    fixed: 'right',
    align: 'right',
    render: row => h(TableActions, {
      actions: actionsFor(row),
    }),
  },
])

function actionsFor(row: GroupRow) {
  const actions: InstanceType<typeof TableActions>['$props']['actions'][number][] = [
    {
      key: 'detail',
      label: t('详情'),
      icon: 'i-fe:eye',
      secondary: true,
      onClick: () => openEditor(row.id, true),
    },
  ]
  if (canEdit.value) {
    actions.push({
      key: 'edit',
      label: t('编辑'),
      icon: 'i-fe:edit-2',
      onClick: () => openEditor(row.id),
    }, {
      key: 'status',
      label: t(row.enabled ? '停用' : '启用'),
      type: row.enabled ? 'error' : 'primary',
      loading: statusWrites.has(row.id),
      onClick: () => confirmStatus(row),
    })
  }
  return actions
}

function loadGroups({ keyword, status, pageNo, pageSize }: Filters & Paging) {
  return api.groups({
    keyword: keyword?.trim() || undefined,
    enabled: status == null
      ? undefined
      : status === 'ENABLED',
    pageNo,
    pageSize,
  })
}

function openEditor(id?: number, readonly = false) {
  editor.value = { id, readonly }
}

function reload() {
  void table.value?.handleSearch(true)
}

function saved() {
  const keepPage = editor.value?.id != null
  editor.value = null
  window.$message.success(t('操作成功'))
  void table.value?.handleSearch(keepPage)
}

function confirmStatus(row: GroupRow) {
  if (statusWrites.has(row.id))
    return
  const enabled = !row.enabled
  const dialog = window.$dialog.warning({
    title: t(enabled ? '启用通知群组' : '停用通知群组'),
    content: enabled
      ? t('启用「{0}」后，可以将其绑定到通知场景。', row.name)
      : t('停用「{0}」后，将无法将其绑定到通知场景。', row.name),
    positiveText: t(enabled ? '启用' : '停用'),
    positiveButtonProps: {
      type: enabled ? 'primary' : 'error',
    },
    negativeText: t('取消'),
    async onPositiveClick() {
      if (statusWrites.has(row.id))
        return false
      statusWrites.add(row.id)
      dialog.loading = true
      try {
        await api.setEnabled(row.id, { enabled, expectedVersion: row.version })
        window.$message.success(t('操作成功'))
      }
      catch (failure) {
        switch (errorReason(failure)) {
          case 'GROUP_IN_USE':
            window.$message.warning(inUseText(failure))
            openEditor(row.id, true)
            break
          case 'VERSION_CONFLICT':
            window.$message.warning(t('群组配置已更新。请核对最新配置后重试。'))
            break
          default:
            window.$message.error(errorMessage(failure, t('保存失败')))
            return false
        }
      }
      finally {
        statusWrites.delete(row.id)
        dialog.loading = false
      }
      reload()
    },
  })
}

watch(() => route.name, (name) => {
  if (name !== 'NotificationGroupView') {
    editor.value = null
    tab.value = 'groups'
  }
})

onMounted(reload)
</script>
