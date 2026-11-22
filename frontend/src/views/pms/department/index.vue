<template>
  <CommonPage>
    <template #action>
      <n-button @click="organizationOpen = true">
        <template #icon>
          <i class="i-material-symbols:account-tree-outline" aria-hidden="true" />
        </template>
        {{ t('组织人员树') }}
      </n-button>
    </template>
    <div class="department-layout">
      <n-card :title="t('组织架构')" size="small" class="department-tree">
        <template #header-extra>
          <n-button v-if="can('DepartmentEdit')" size="small" type="primary" @click="openEditor()">
            {{ t('新增部门') }}
          </n-button>
        </template>
        <n-spin :show="loadingTree">
          <n-alert v-if="treeFailed" type="error">
            {{ t('加载失败') }}
            <n-button text @click="loadTree">
              {{ t('重试') }}
            </n-button>
          </n-alert>
          <n-input v-model:value="treeSearch" clearable class="mb-12" :placeholder="t('搜索部门')" />
          <n-tree
            block-line
            default-expand-all
            :data="tree"
            :pattern="treeSearch"
            :selected-keys="[selected]"
            @update:selected-keys="selectDepartment"
          />
        </n-spin>
      </n-card>

      <n-card :title="current?.name || t('未分配部门')" size="small" class="department-members">
        <template v-if="can('DepartmentMembers') && current?.enabled" #header-extra>
          <n-button size="small" type="primary" @click="memberPicker = true">
            <i class="i-material-symbols:add mr-4 text-18" aria-hidden="true" />
            {{ t('添加成员') }}
          </n-button>
        </template>
        <div v-if="current" class="mb-12 flex flex-wrap items-center gap-x-12 gap-y-8">
          <NTag :type="current.enabled ? 'success' : 'default'" size="small">
            {{ current.enabled ? t('启用') : t('停用') }}
          </NTag>
          <span>{{ t('负责人') }} · <PersonLink :user-id="current.leaderUserId" :label="current.leaderName || t('待指定')" /></span>
          <n-button v-if="can('DepartmentEdit')" size="small" @click="openEditor(current)">
            {{ t('编辑部门') }}
          </n-button>
          <n-button v-if="can('DepartmentLeader') && current.enabled" size="small" @click="leaderPicker = true">
            {{ t('设置负责人') }}
          </n-button>
          <n-button v-if="can('DepartmentLeader') && current.leaderUserId" size="small" :loading="clearingLeader" @click="confirmClearLeader">
            {{ t('清空负责人') }}
          </n-button>
        </div>
        <MeCrud
          ref="memberTable"
          v-model:query-items="queryItems"
          :scroll-x="tableScrollWidth"
          :page-size="20"
          :columns="columns"
          :get-data="readMembers"
          :checked-row-keys="checked"
          class="min-h-0 flex-1"
          @on-checked="updateChecked"
          @on-data-change="updateMembers"
          @update:query-failed="memberFailed = $event"
        >
          <MeQueryItem :label="t('姓名或账号')" :label-width="75" :content-width="200">
            <n-input v-model:value="queryItems.keyword" clearable :maxlength="100" :placeholder="t('搜索姓名或账号')" />
          </MeQueryItem>
          <MeQueryItem :label="t('状态')" :label-width="50" :content-width="106">
            <n-select v-model:value="queryItems.enable" :options="statusOptions" clearable />
          </MeQueryItem>

          <template #toolbar>
            <div v-if="current || memberActions.length" class="mb-12 flex flex-wrap items-center justify-between gap-8">
              <n-checkbox v-if="current" v-model:checked="includeDescendants">
                {{ t('包含下级部门') }}
              </n-checkbox>
              <div v-if="memberActions.length" class="ml-auto flex flex-wrap justify-end gap-8">
                <n-button
                  v-for="action in memberActions"
                  :key="action.key"
                  size="small"
                  :type="action.type"
                  :secondary="action.secondary"
                  :disabled="loadingMembers || !checked.length"
                  @click="openChange(action.key, checkedMembers)"
                >
                  {{ action.label }}
                </n-button>
              </div>
            </div>
            <n-alert v-if="memberFailed" class="mb-12" type="error">
              {{ t('加载失败') }}
              <n-button text @click="refreshMembers(true)">
                {{ t('重试') }}
              </n-button>
            </n-alert>
          </template>
        </MeCrud>
      </n-card>
    </div>

    <n-modal v-model:show="editorOpen" preset="card" :title="editingId ? t('编辑部门') : t('新增部门')" style="width: min(500px, 94vw)" :mask-closable="!saving" :closable="!saving" :close-on-esc="!saving">
      <n-form :model="form" label-placement="left" label-width="auto">
        <n-form-item :label="t('部门名称')" required>
          <n-input v-model:value="form.name" :maxlength="100" />
        </n-form-item>
        <n-form-item :label="t('上级部门')">
          <n-select v-model:value="form.parentId" :options="parentOptions" filterable clearable :placeholder="t('顶级部门')" />
        </n-form-item>
        <n-form-item :label="t('排序')">
          <n-input-number v-model:value="form.sortOrder" :min="0" :max="2147483647" :precision="0" />
        </n-form-item>
        <n-form-item :label="t('启用')">
          <n-switch v-model:value="form.enabled" />
        </n-form-item>
        <n-alert v-if="editingId && !form.enabled" type="warning">
          {{ t('停用前须迁出成员并停用直属下级部门。停用时会清空该部门负责人。') }}
        </n-alert>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button :disabled="saving" @click="editorOpen = false">
            {{ t('取消') }}
          </n-button>
          <n-button type="primary" :loading="saving" :disabled="!form.name.trim() || form.sortOrder == null" @click="saveDepartment">
            {{ t('保存') }}
          </n-button>
        </n-space>
      </template>
    </n-modal>

    <n-modal v-model:show="moveOpen" preset="card" :title="changeTitle" style="width: min(500px, 94vw)" :mask-closable="!moving" :closable="!moving" :close-on-esc="!moving">
      <n-form-item v-if="changeAction !== 'REMOVE'" :label="t('目标部门')" required>
        <n-select v-model:value="destination" :options="changeTargets" filterable />
      </n-form-item>
      <p v-if="changeAction === 'REMOVE'">
        {{ t('从「{0}」移出 {1} 名成员，其他归属保留。', changeSource?.name, changeUsers.length) }}
      </p>
      <p v-else-if="changeAction === 'TRANSFER'">
        {{ t('从「{0}」调动 {1} 名成员，其他归属保留。', changeSource?.name, changeUsers.length) }}
      </p>
      <p v-else>
        {{ t('为 {0} 名用户分配部门。', changeUsers.length) }}
      </p>
      <template #footer>
        <n-space justify="end">
          <n-button :disabled="moving" @click="moveOpen = false">
            {{ t('取消') }}
          </n-button>
          <n-button :type="changeAction === 'REMOVE' ? 'error' : 'primary'" :loading="moving" :disabled="changeAction !== 'REMOVE' && destination == null" @click="saveMove">
            {{ t('确定') }}
          </n-button>
        </n-space>
      </template>
    </n-modal>

    <MemberPicker
      v-if="current"
      v-model:show="leaderPicker"
      :title="t('设置部门负责人')"
      single
      :department-id="current.id"
      :leader-id="current.leaderUserId"
      :save="setLeader"
    />
    <MemberPicker v-model:show="memberPicker" :title="t('添加成员')" :save="addMembers" />
    <OrganizationTreeModal v-model:show="organizationOpen" />
  </CommonPage>
</template>

<script setup lang="ts">
import type { DataTableColumns, TreeOption } from 'naive-ui'
import type { Department, DepartmentForm, Member, MemberAction, MemberChange, VersionedUser } from './api'
import { useElementSize } from '@vueuse/core'
import { NTag } from 'naive-ui'
import { computed, h, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AccountAvatar from '@/components/common/AccountAvatar.vue'
import PersonLink from '@/components/common/PersonLink.vue'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import MeQueryItem from '@/components/me/crud/QueryItem.vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import { useAppStore, useUserStore } from '@/store'
import api from './api'
import MemberPicker from './MemberPicker.vue'
import OrganizationTreeModal from './OrganizationTreeModal.vue'

defineOptions({ name: 'DepartmentView' })

const route = useRoute()
const user = useUserStore()
const appStore = useAppStore()
const isCompactTable = computed(() => appStore.tableSize === 'small')
const departments = ref<Department[]>([])
const loadingTree = ref(false)
const treeFailed = ref(false)
const treeSearch = ref('')
const unassigned = 'unassigned'
const selected = ref<number | string>(unassigned)
const memberTable = ref<InstanceType<typeof MeCrud> | null>(null)
const { width: tableWidth } = useElementSize(() => memberTable.value?.$el as HTMLElement | undefined)
const inlineActionsWidth = computed(() => selected.value === unassigned
  ? 140
  : 270)
const dropdownActionsWidth = 72
const useActionsDropdown = computed(() => tableWidth.value > 0 && tableWidth.value < inlineActionsWidth.value + 360)
const members = ref<Member[]>([])
interface MemberFilters {
  keyword: string | null
  enable: 1 | 0 | null
}
const queryItems = ref<MemberFilters>({ keyword: null, enable: null })
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
const includeDescendants = ref(false)
const loadingMembers = ref(false)
const memberFailed = ref(false)
const checked = ref<number[]>([])
const checkedMembers = computed(() => members.value.filter(member => checked.value.includes(member.id)))
const editorOpen = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = ref<DepartmentForm>({
  parentId: null,
  name: '',
  sortOrder: 0,
  enabled: true,
})
const moveOpen = ref(false)
const moving = ref(false)
const destination = ref<number | null>(null)
const changeAction = ref<MemberAction>('ADD')
const changeSource = ref<Department>()
const changeUsers = ref<VersionedUser[]>([])
const changeUsername = ref('')
const actionLabels = {
  ADD: '分配部门',
  REMOVE: '从本部门移出',
  TRANSFER: '调至其他部门',
}
const changeTitle = computed(() => {
  const label = t(actionLabels[changeAction.value])
  return changeUsername.value
    ? `${label} · ${changeUsername.value}`
    : label
})
const leaderPicker = ref(false)
const memberPicker = ref(false)
const organizationOpen = ref(false)
const clearingLeader = ref(false)

function can(code: string) {
  const buttons = route.meta.btns
  return user.currentRole.code === 'SUPER_ADMIN'
    || (Array.isArray(buttons) && buttons.some(button => button.code === code))
}

const byId = computed(() => new Map(departments.value.map(department => [department.id, department])))
const departmentsByLeader = computed(() => {
  const leaders = new Map<number, string[]>()
  for (const department of departments.value) {
    if (department.leaderUserId == null)
      continue
    const names = leaders.get(department.leaderUserId) ?? []
    names.push(department.name)
    leaders.set(department.leaderUserId, names)
  }
  return leaders
})
const current = computed(() => typeof selected.value === 'number'
  ? byId.value.get(selected.value)
  : undefined)
const memberActions = computed(() => {
  if (!can('DepartmentMembers') || includeDescendants.value)
    return []
  if (current.value) {
    return [
      {
        key: 'TRANSFER',
        label: t(actionLabels.TRANSFER),
        type: 'primary',
        secondary: true,
      },
      {
        key: 'REMOVE',
        label: t(actionLabels.REMOVE),
        type: 'error',
        secondary: true,
      },
    ] as const
  }
  return selected.value === unassigned
    ? [
        {
          key: 'ADD',
          label: t(actionLabels.ADD),
          type: 'primary',
          secondary: true,
        },
      ] as const
    : []
})
const tree = computed<TreeOption[]>(() => {
  const roots: TreeOption[] = [
    {
      key: unassigned,
      label: t('未分配部门'),
    },
  ]
  const nodes = new Map<number, TreeOption>()
  for (const department of departments.value) {
    nodes.set(department.id, {
      key: department.id,
      label: department.enabled
        ? department.name
        : `${department.name} · ${t('停用')}`,
    })
  }
  for (const department of departments.value) {
    const node = nodes.get(department.id)!
    const parent = department.parentId == null
      ? undefined
      : nodes.get(department.parentId)
    if (parent) {
      parent.children ??= []
      parent.children.push(node)
    }
    else {
      roots.push(node)
    }
  }
  return roots
})

function path(department: Department) {
  const names = [department.name]
  let cursor = department.parentId
  while (cursor != null) {
    const parent = byId.value.get(cursor)
    if (!parent)
      break
    names.unshift(parent.name)
    cursor = parent.parentId
  }
  return names.join(' / ')
}

function withinEditedSubtree(department: Department) {
  let cursor: number | null = department.id
  while (cursor != null) {
    if (cursor === editingId.value)
      return true
    cursor = byId.value.get(cursor)?.parentId ?? null
  }
  return false
}

const targetOptions = computed(() => departments.value.filter(department => department.enabled)
  .map(department => ({
    value: department.id,
    label: path(department),
  })))
const changeTargets = computed(() => targetOptions.value.filter(option => option.value !== changeSource.value?.id))
const parentOptions = computed(() => departments.value.filter(department => !withinEditedSubtree(department)
  && (department.enabled || department.id === byId.value.get(editingId.value ?? 0)?.parentId))
  .map(department => ({
    value: department.id,
    label: path(department),
  })))
const columns = computed<DataTableColumns<Member>>(() => [
  ...(memberActions.value.length
    ? [
        { type: 'selection', width: 44, fixed: 'left' } as const,
      ]
    : []),
  {
    title: t('头像'),
    key: 'avatar',
    width: 80,
    render: row => h(PersonLink, {
      userId: row.id,
      label: row.username,
      ariaLabel: t('查看 {0} 的人员信息', row.username),
      style: {
        '--account-avatar-icon-size': 'var(--n-font-size)',
        'display': isCompactTable.value ? 'inline-flex' : undefined,
      },
    }, {
      default: () => h(AccountAvatar, {
        src: row.avatar,
        size: isCompactTable.value ? 22 : appStore.tableSize,
      }),
    }),
  },
  {
    title: t('用户名'),
    key: 'username',
    render: row => h(PersonLink, { userId: row.id, label: row.username }),
    width: 150,
    ellipsis: { tooltip: true },
  },
  {
    title: t('姓名'),
    key: 'name',
    width: 185,
    render: (row) => {
      const name = displayName(row.nameCn || row.username, row.nameEn)
      const leadership = departmentsByLeader.value.get(row.id)
      return h('div', { class: 'flex items-center gap-6' }, [
        h('span', { class: 'min-w-0 truncate', title: name }, [
          h(PersonLink, { userId: row.id, label: name }),
        ]),
        leadership && h(NTag, {
          size: 'small',
          type: 'info',
          class: 'shrink-0',
          title: leadership.join('、'),
        }, () => t('负责人')),
      ])
    },
  },
  {
    title: t('主部门'),
    key: 'departmentName',
    width: 160,
    ellipsis: { tooltip: true },
    render: row => row.departmentName || t('未分配部门'),
  },
  {
    title: t('兼岗部门'),
    key: 'secondaryDepartments',
    width: 180,
    ellipsis: { tooltip: true },
    render: row => row.departments.filter(department => department.id !== row.departmentId).map(department => department.name).join('、') || '—',
  },
  {
    title: t('命中的部门'),
    key: 'matchedDepartments',
    width: 180,
    ellipsis: { tooltip: true },
    render: row => row.matchedDepartments.map(department => `${department.name} · ${t(department.id === row.departmentId
      ? '主部门'
      : '兼岗')}`).join('、') || '—',
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
  ...(memberActions.value.length
    ? [
        {
          title: t('操作'),
          key: 'actions',
          width: useActionsDropdown.value ? dropdownActionsWidth : inlineActionsWidth.value,
          align: 'right' as const,
          fixed: 'right' as const,
          render: (row: Member) => h(TableActions, {
            actions: memberActions.value.map(action => ({
              ...action,
              disabled: loadingMembers.value,
              onClick: () => openChange(action.key, [row]),
            })),
            dropdown: useActionsDropdown.value || undefined,
            size: isCompactTable.value ? 'tiny' : 'small',
          }),
        },
      ]
    : []),
])
const tableScrollWidth = computed(() => columns.value.reduce((width, column) => width + Number(column.width), 0))

async function loadTree() {
  loadingTree.value = true
  treeFailed.value = false
  try {
    departments.value = (await api.list()).data
  }
  catch {
    treeFailed.value = true
  }
  finally {
    loadingTree.value = false
  }
}

function readMembers({ keyword, enable, pageNo, pageSize }: MemberFilters & { pageNo: number, pageSize: number }) {
  loadingMembers.value = true
  checked.value = []
  return api.members({
    departmentId: typeof selected.value === 'number'
      ? selected.value
      : undefined,
    unassigned: selected.value === unassigned,
    includeDescendants: includeDescendants.value,
    keyword: keyword?.trim(),
    enable: enable == null
      ? undefined
      : enable === 1,
    pageNo,
    pageSize,
  })
}

function updateChecked(keys: (number | string)[]) {
  if (!loadingMembers.value)
    checked.value = keys.filter(key => typeof key === 'number')
}

function updateMembers(rows: Member[]) {
  members.value = rows
  loadingMembers.value = false
}

function refreshMembers(keepCurrentPage = false) {
  return memberTable.value?.handleSearch(keepCurrentPage)
}

watch([selected, includeDescendants], () => refreshMembers())
onMounted(() => {
  loadTree()
  refreshMembers()
})

function selectDepartment(keys: (number | string)[]) {
  if (keys.length) {
    selected.value = keys[0]!
    if (selected.value === unassigned)
      includeDescendants.value = false
  }
}

function openEditor(department?: Department) {
  editingId.value = department?.id ?? null
  form.value = department
    ? {
        name: department.name,
        parentId: department.parentId,
        enabled: department.enabled,
        sortOrder: department.sortOrder,
      }
    : {
        name: '',
        parentId: current.value?.enabled ? current.value.id : null,
        enabled: true,
        sortOrder: 0,
      }
  editorOpen.value = true
}

async function saveDepartment() {
  saving.value = true
  try {
    if (editingId.value == null)
      selected.value = (await api.create(form.value)).data
    else
      await api.update(editingId.value, form.value)
    editorOpen.value = false
    window.$message.success(t('操作成功'))
    await loadTree()
  }
  catch {
    // 保留表单供修改。
  }
  finally {
    saving.value = false
  }
}

async function setLeader(users: VersionedUser[]) {
  if (!current.value)
    return
  await api.setLeader(current.value.id, users[0]?.userId ?? null)
  window.$message.success(t('操作成功'))
  await loadTree()
}

function confirmClearLeader() {
  window.$dialog.warning({
    title: t('清空负责人'),
    content: t('确定清空此部门的负责人？'),
    positiveText: t('确定'),
    negativeText: t('取消'),
    async onPositiveClick() {
      clearingLeader.value = true
      try {
        await setLeader([])
      }
      catch {
        return false
      }
      finally {
        clearingLeader.value = false
      }
    },
  })
}

async function addMembers(users: VersionedUser[]) {
  if (current.value)
    await submitChange({ action: 'ADD', targetDepartmentId: current.value.id, users })
}

function openChange(action: MemberAction, rows: Member[]) {
  if (loadingMembers.value || !rows.length || !memberActions.value.some(item => item.key === action))
    return
  changeAction.value = action
  changeSource.value = current.value
  changeUsers.value = rows.map(member => ({ userId: member.id, expectedVersion: member.departmentVersion }))
  changeUsername.value = rows.length === 1
    ? rows[0]!.username
    : ''
  destination.value = null
  moveOpen.value = true
}

async function saveMove() {
  moving.value = true
  try {
    await submitChange({
      action: changeAction.value,
      sourceDepartmentId: changeSource.value?.id,
      targetDepartmentId: destination.value ?? undefined,
      users: changeUsers.value,
    })
    moveOpen.value = false
  }
  catch (error) {
    if ((error as { code?: number } | null)?.code === 409) {
      moveOpen.value = false
      await refreshMembers(true)
    }
  }
  finally {
    moving.value = false
  }
}

async function submitChange(data: MemberChange) {
  await api.changeMembers(data)
  window.$message.success(t('操作成功'))
  await refreshMembers(true)
}
</script>

<style scoped>
.department-layout {
  display: grid;
  grid-template-columns: minmax(230px, 285px) minmax(0, 1fr);
  align-items: start;
  gap: 16px;
  height: 100%;
  min-height: 520px;
}

.department-members {
  align-self: stretch;
  min-height: 0;
  container: department-members / inline-size;
}

.department-members :deep(.n-card__content) {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

@container department-members (max-width: 600px) {
  .department-members :deep(.me-crud-query) {
    flex-direction: column;
  }

  .department-members :deep(.me-crud-query-actions) {
    align-self: flex-end;
  }
}

.department-tree :deep(.n-tree) {
  max-height: 65vh;
  overflow: auto;
}

@media (max-width: 760px) {
  .department-layout {
    grid-template-columns: minmax(0, 1fr);
    height: auto;
  }

  .department-members {
    height: max(520px, 65vh);
  }

  .department-tree :deep(.n-tree) {
    max-height: 35vh;
  }
}
</style>
