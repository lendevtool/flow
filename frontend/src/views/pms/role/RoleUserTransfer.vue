<template>
  <CommonPage back>
    <template #title-suffix>
      <NTag v-if="roleName" class="ml-12" type="warning">
        {{ roleName }}
      </NTag>
    </template>
    <template #action>
      <div v-if="validRole" class="flex flex-wrap justify-end gap-8">
        <NButton size="small" @click="showHandlers = true">
          {{ t('流程主备') }}
        </NButton>
        <NButton size="small" @click="openClassic">
          {{ t('原分配页面') }}
        </NButton>
      </div>
    </template>

    <NAlert v-if="!validRole" type="error">
      {{ t('角色异常，请重新选择角色') }}
    </NAlert>
    <div v-else class="transfer-container">
      <div class="transfer-grid">
        <template v-for="side in sides" :key="side.label">
          <NCard tag="section" size="small" class="min-w-0" :title="t(side.label)" :aria-label="t(side.label)">
            <template #header-extra>
              <NTag v-if="!side.failed && !side.loading" size="small" round>
                {{ side.total }}
              </NTag>
            </template>

            <template v-if="side.assigned">
              <NAlert v-if="missingPrimary" type="warning" class="mb-12">
                {{ t('此角色的流程单人节点需要主办，请将一名启用成员设为主办。') }}
                <NButton text type="primary" class="ml-8" @click="showHandlers = true">
                  {{ t('配置主办') }}
                </NButton>
              </NAlert>
              <NAlert v-else-if="handlersFailed" type="error" class="mb-12">
                {{ t('流程主备加载失败') }}
                <NButton text @click="loadHandlers">
                  {{ t('重试') }}
                </NButton>
              </NAlert>
            </template>

            <form class="mb-12 flex gap-8" @submit.prevent="search(side)">
              <NInput
                v-model:value="side.searchText"
                size="small"
                :placeholder="t('搜索姓名或账号')"
                :input-props="{ 'aria-label': t('搜索姓名或账号') }"
                :disabled="!!moving"
                clearable
                @clear="search(side, '')"
              />
              <NButton size="small" attr-type="submit" :disabled="!!moving">
                {{ t('搜索') }}
              </NButton>
            </form>

            <NAlert v-if="side.failed" type="error">
              {{ t('加载失败') }}
              <NButton text :disabled="!!moving" @click="load(side)">
                {{ t('重试') }}
              </NButton>
            </NAlert>
            <NDataTable
              v-else
              v-model:checked-row-keys="side.checked"
              :aria-label="t(side.label)"
              :columns="side.assigned ? assignedColumns : columns"
              :data="side.rows"
              :loading="side.loading"
              :pagination="{ page: side.page, itemCount: side.total, pageSize, simple: true, disabled: !!moving }"
              :row-key="row => row.id"
              :scroll-x="side.assigned ? 448 : 320"
              max-height="clamp(180px, 50vh, 520px)"
              remote
              size="small"
              @update:page="changePage(side, $event)"
            >
              <template #empty>
                {{ t('没有数据') }}
              </template>
            </NDataTable>
          </NCard>

          <div v-if="!side.assigned" class="transfer-buttons">
            <NButton
              type="primary"
              secondary
              :disabled="!canMove(available)"
              :loading="moving === 'add'"
              @click="move(available)"
            >
              {{ t('移入') }}
              <span class="transfer-arrow" aria-hidden="true">→</span>
            </NButton>
            <NPopconfirm
              :positive-text="t('移出')"
              :negative-text="t('取消')"
              :on-positive-click="() => move(assigned)"
            >
              <template #trigger>
                <NButton :disabled="!canMove(assigned)" :loading="moving === 'remove'">
                  <span class="transfer-arrow" aria-hidden="true">←</span>
                  {{ t('移出') }}
                </NButton>
              </template>
              {{ t('确认移出 {0} 名用户？', assigned.checked.length) }}
            </NPopconfirm>
          </div>
        </template>
      </div>
    </div>
    <WorkflowHandlers v-if="showHandlers" :role-id="roleId" @close="showHandlers = false" @saved="loadHandlers" />
  </CommonPage>
</template>

<script setup lang="ts">
import type { DataTableColumns, DataTableRowKey } from 'naive-ui'
import type { TransferUser } from './user-transfer-api'
import type { HandlerSummary } from '@/views/workbench/workflow/api'
import { NAlert, NButton, NCard, NDataTable, NInput, NPopconfirm, NTag } from 'naive-ui'
import { computed, h, onScopeDispose, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CommonPage from '@/components/common/CommonPage.vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import { navigationLabel } from '@/i18n/navigation'
import workflow from '@/views/workbench/workflow/api'
import api from './api'
import { readTransferUsers } from './user-transfer-api'
import WorkflowHandlers from './WorkflowHandlers.vue'

defineOptions({ name: 'RoleUserTransfer' })

interface TransferSide {
  label: string
  assigned: boolean
  searchText: string
  keyword: string
  page: number
  total: number
  rows: TransferUser[]
  checked: DataTableRowKey[]
  loading: boolean
  failed: boolean
  request?: AbortController
}

const route = useRoute()
const router = useRouter()
const roleId = computed(() => Number(route.params.roleId))
const validRole = computed(() => Number.isSafeInteger(roleId.value) && roleId.value > 0)
const roleName = computed(() => navigationLabel(route.query.roleCode, route.query.roleName, route.query.roleNameEn))
const showHandlers = ref(false)
const handlers = ref<HandlerSummary>()
const handlersFailed = ref(false)
const missingPrimary = computed(() => handlers.value?.requiresPrimaryHandler && !handlers.value.primaryEnabled)
const backupUserIds = computed(() => new Set(handlers.value?.backupUserIds))
let handlerRequest: AbortController | undefined
const moving = ref<'add' | 'remove' | null>(null)
const pageSize = 20

function createSide(label: string, assigned: boolean) {
  return reactive<TransferSide>({
    label,
    assigned,
    searchText: '',
    keyword: '',
    page: 1,
    total: 0,
    rows: [],
    checked: [],
    loading: false,
    failed: false,
  })
}

const available = createSide('未分配用户', false)
const assigned = createSide('已分配用户', true)
const sides = [available, assigned]

const columns = computed<DataTableColumns<TransferUser>>(() => [
  {
    type: 'selection',
    width: 36,
    disabled: () => !!moving.value,
  },
  {
    title: t('用户名'),
    key: 'username',
    minWidth: 86,
    ellipsis: { tooltip: true },
  },
  {
    title: t('姓名'),
    key: 'name',
    minWidth: 88,
    ellipsis: { tooltip: true },
    render: row => displayName(row.nameCn, row.nameEn) || '—',
  },
  {
    title: t('所属部门'),
    key: 'departments',
    minWidth: 110,
    ellipsis: { tooltip: true },
    render(row) {
      const otherNames = (row.departments ?? [])
        .filter(department => department.id !== row.departmentId)
        .map(department => department.name)
      return [row.departmentName, ...otherNames].filter(Boolean).join(' / ') || t('未分配部门')
    },
  },
])

const assignedColumns = computed<DataTableColumns<TransferUser>>(() => [
  ...columns.value,
  {
    title: t('主备'),
    key: 'workflowHandler',
    minWidth: 128,
    render(row) {
      const summary = handlers.value
      if (summary && row.id === summary.primaryUserId) {
        const enabled = summary.primaryEnabled
        return h(NTag, {
          size: 'small',
          type: enabled ? 'info' : 'warning',
        }, () => t(enabled ? '主办' : '主办（已停用）'))
      }
      if (backupUserIds.value.has(row.id))
        return h(NTag, { size: 'small' }, () => t('备办'))
      return '—'
    },
  },
])

async function loadHandlers() {
  handlerRequest?.abort()
  handlers.value = undefined
  handlersFailed.value = false
  if (!validRole.value)
    return
  const controller = new AbortController()
  handlerRequest = controller
  try {
    const { data } = await workflow.handlerSummary(roleId.value, controller.signal)
    if (!controller.signal.aborted)
      handlers.value = data
  }
  catch {
    if (!controller.signal.aborted)
      handlersFailed.value = true
  }
  finally {
    if (handlerRequest === controller)
      handlerRequest = undefined
  }
}

function openClassic() {
  return router.push({
    name: 'RoleUser',
    params: { roleId: roleId.value },
    query: route.query,
  })
}

async function load(side: TransferSide) {
  side.request?.abort()
  if (!validRole.value)
    return
  const controller = new AbortController()
  side.request = controller
  side.loading = true
  side.failed = false
  side.rows = []
  side.checked = []
  try {
    const { data } = await readTransferUsers({
      roleId: roleId.value,
      roleAssigned: side.assigned,
      keyword: side.keyword,
      pageNo: side.page,
      pageSize,
    }, controller.signal)
    if (controller.signal.aborted)
      return
    side.total = data.total
    const lastPage = Math.max(1, Math.ceil(data.total / pageSize))
    // 移出当前页最后一批用户时，回到仍有数据的最后一页。
    if (side.page > lastPage) {
      side.page = lastPage
      return await load(side)
    }
    side.rows = data.pageData
  }
  catch {
    if (!controller.signal.aborted)
      side.failed = true
  }
  finally {
    if (side.request === controller) {
      side.loading = false
      side.request = undefined
    }
  }
}

function search(side: TransferSide, text = side.searchText) {
  if (moving.value)
    return
  side.keyword = text.trim()
  side.page = 1
  return load(side)
}

function changePage(side: TransferSide, page: number) {
  if (moving.value)
    return
  side.page = page
  return load(side)
}

function canMove(side: TransferSide) {
  return !moving.value && !side.loading && !side.failed && side.checked.length > 0
}

async function move(side: TransferSide) {
  const id = roleId.value
  if (!validRole.value || !canMove(side))
    return
  const userIds = side.checked.filter((key): key is number => typeof key === 'number')
  moving.value = side.assigned ? 'remove' : 'add'
  try {
    const update = side.assigned ? api.removeRoleUsers : api.addRoleUsers
    await update(id, { userIds })
    window.$message.success(t('操作成功'))
    if (validRole.value && roleId.value === id) {
      await Promise.all([
        ...sides.map(load),
        loadHandlers(),
      ])
    }
  }
  catch {
    // 统一拦截器提示失败，保留勾选以便重试。
  }
  finally {
    moving.value = null
  }
}

watch(roleId, () => {
  showHandlers.value = false
  void loadHandlers()
  for (const side of sides) {
    Object.assign(side, {
      searchText: '',
      keyword: '',
      page: 1,
      total: 0,
      rows: [],
      checked: [],
    })
    load(side)
  }
}, { immediate: true })

onScopeDispose(() => {
  handlerRequest?.abort()
  for (const side of sides)
    side.request?.abort()
})
</script>

<style scoped>
.transfer-container {
  container-type: inline-size;
}

.transfer-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: start;
  gap: 16px;
}

.transfer-buttons {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-block: 88px 0;
}

.transfer-arrow {
  display: inline-block;
  margin-inline: 3px;
}

@container (max-width: 780px) {
  .transfer-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .transfer-buttons {
    flex-direction: row;
    justify-content: center;
    margin-block: 0;
  }

  .transfer-arrow {
    transform: rotate(90deg);
  }
}
</style>
