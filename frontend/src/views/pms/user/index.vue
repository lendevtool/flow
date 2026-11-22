<template>
  <CommonPage>
    <template #action>
      <NButton v-permission="'AddUser'" type="primary" @click="openCreate">
        <i class="i-material-symbols:add mr-4 text-18" />
        {{ t('创建新用户') }}
      </NButton>
    </template>

    <MeCrud
      ref="$table"
      v-model:query-items="queryItems"
      :scroll-x="tableScrollWidth"
      :columns="columns"
      :get-data="readUsers"
    >
      <MeQueryItem :label="t('用户名')" :label-width="50">
        <n-input
          v-model:value="queryItems.username"
          type="text"
          :placeholder="t('请输入用户名')"
          clearable
        />
      </MeQueryItem>

      <MeQueryItem :label="t('性别')" :label-width="50">
        <n-select v-model:value="queryItems.gender" clearable :options="genders" />
      </MeQueryItem>

      <MeQueryItem :label="t('状态')" :label-width="50">
        <n-select
          v-model:value="queryItems.enable"
          clearable
          :options="[
            { label: t('启用'), value: 1 },
            { label: t('停用'), value: 0 },
          ]"
        />
      </MeQueryItem>
      <MeQueryItem :label="t('所属部门')" :label-width="65">
        <n-select v-model:value="queryItems.department" :options="departmentFilters" filterable clearable />
      </MeQueryItem>
      <MeQueryItem v-if="typeof queryItems.department === 'number'" :label="t('部门范围')" :label-width="65">
        <n-select v-model:value="queryItems.departmentScope" :options="departmentScopes" :placeholder="t('任意归属')" />
      </MeQueryItem>
    </MeCrud>

    <MeModal ref="modalRef" width="520px">
      <n-form
        ref="modalFormRef"
        label-placement="left"
        label-align="left"
        :label-width="105"
        :model="modalForm"
        :disabled="modalAction === 'view'"
      >
        <n-form-item
          :label="t('用户名')"
          path="username"
          :rule="{
            required: true,
            message: t('请输入用户名'),
            trigger: ['input', 'blur'],
          }"
        >
          <n-input v-model:value="modalForm.username" :disabled="modalAction !== 'add'" />
        </n-form-item>
        <template v-if="['add', 'edit'].includes(modalAction)">
          <n-form-item :label="t('中文姓名')" path="nameCn">
            <n-input v-model:value="modalForm.nameCn" :maxlength="100" :placeholder="t('选填')" />
          </n-form-item>
          <n-form-item :label="t('英文名称')" path="nameEn">
            <n-input v-model:value="modalForm.nameEn" :maxlength="100" :placeholder="t('选填')" />
          </n-form-item>
          <n-form-item :label="t('域账号')" path="domainAccount">
            <n-input v-model:value="modalForm.domainAccount" :maxlength="254" placeholder="user@company.example" />
          </n-form-item>
          <n-form-item :label="t('手机号')" path="mobile">
            <n-input
              v-model:value="modalForm.mobile"
              :maxlength="16"
              :input-props="{ inputmode: 'tel' }"
              :placeholder="t('选填，可带国际区号')"
            />
          </n-form-item>
          <n-form-item :label="t('所属部门')" path="departmentIds">
            <n-select :value="modalForm.departmentIds" :options="activeDepartments" multiple filterable clearable :loading="loadingDepartments" @update:value="selectDepartments" />
          </n-form-item>
          <n-form-item v-if="modalForm.departmentIds?.length" :label="t('主部门')" path="primaryDepartmentId" :rule="primaryDepartmentRule">
            <n-select v-model:value="modalForm.primaryDepartmentId" :options="primaryOptions" filterable />
          </n-form-item>
          <n-alert v-if="originalMemberships.ids.length && !modalForm.departmentIds?.length" type="warning" class="mb-12">
            {{ t('保存后将清空该用户的全部部门归属。') }}
          </n-alert>
          <n-alert v-if="departmentConflict" type="warning" class="mb-12">
            {{ t('部门归属已变更，请刷新后重试') }}
            <NButton text :loading="loadingDepartments" @click="reloadMemberships">
              {{ t('刷新归属') }}
            </NButton>
          </n-alert>
        </template>
        <n-form-item v-if="modalAction === 'add'" :label="t('角色')" path="roleIds">
          <n-select
            v-model:value="modalForm.roleIds"
            :options="roleOptions"
            :loading="loadingRoles"
            value-field="id"
            clearable
            filterable
            multiple
          />
        </n-form-item>
        <n-form-item v-if="modalAction === 'add'" :label="t('状态')" path="enable">
          <NSwitch v-model:value="modalForm.enable">
            <template #checked>
              {{ t('启用') }}
            </template>
            <template #unchecked>
              {{ t('停用') }}
            </template>
          </NSwitch>
        </n-form-item>
      </n-form>
      <template v-if="modalAction === 'setRole'">
        <n-alert v-if="roleLoadFailed" type="error">
          {{ t('加载失败，请重试') }}
          <NButton text class="ml-8" @click="refreshRoles">
            {{ t('重试') }}
          </NButton>
        </n-alert>
        <n-spin v-else :show="loadingRoles">
          <UserRoleTransfer
            :key="modalForm.id"
            v-model="modalForm.roleIds"
            :options="assignmentRoleOptions"
            :disabled="loadingRoles || okLoading"
          />
        </n-spin>
      </template>
      <n-alert v-if="['add', 'reset'].includes(modalAction)" type="info" class="mb-12">
        {{ t('保存后将生成临时密码，仅展示一次。') }}
      </n-alert>
      <n-alert v-if="modalAction === 'add'" type="warning" closable>
        {{ t('详细信息需由用户本人补充修改') }}
      </n-alert>
    </MeModal>
    <n-modal
      :show="Boolean(temporaryCredential)"
      preset="card"
      :title="t('临时密码')"
      style="width: min(480px, 92vw)"
      @update:show="temporaryCredential = null"
    >
      <template v-if="temporaryCredential">
        <p class="mb-12">
          {{ t('请通过受控渠道交付临时密码。关闭后将无法再次查看。') }}
        </p>
        <n-input :value="temporaryCredential.temporaryPassword" readonly :aria-label="t('临时密码')" />
        <p class="mt-12">
          {{ t('有效至 {0}', formatDateTime(temporaryCredential.expiresAt)) }}
        </p>
      </template>
    </n-modal>
  </CommonPage>
</template>

<script setup>
import { useElementSize } from '@vueuse/core'
import { NButton, NSwitch, NTag } from 'naive-ui'
import { MeCrud, MeModal, MeQueryItem } from '@/components'
import AccountAvatar from '@/components/common/AccountAvatar.vue'
import PersonLink from '@/components/common/PersonLink.vue'
import TableActions from '@/components/common/TableActions.vue'
import { useCrud } from '@/composables'
import { t } from '@/i18n'
import { resourceLabel } from '@/i18n/navigation'
import { useAppStore, useUserStore } from '@/store'
import { formatDateTime } from '@/utils'
import departmentApi from '../department/api'
import api from './api'
import UserRoleTransfer from './UserRoleTransfer.vue'

defineOptions({ name: 'UserMgt' })

const $table = ref(null)
const { width: tableWidth } = useElementSize($table)
const inlineActionsWidth = 420
const dropdownActionsWidth = 72
// 固定操作列展开时，为左侧的用户信息至少保留 360px。
const useActionsDropdown = computed(() => tableWidth.value > 0 && tableWidth.value < inlineActionsWidth + 360)
const appStore = useAppStore()
const isCompactTable = computed(() => appStore.tableSize === 'small')
const userStore = useUserStore()
/** QueryBar筛选参数（可选） */
const queryItems = ref({})
const temporaryCredential = ref(null)

const departments = ref([])
const loadingDepartments = ref(false)
const departmentConflict = ref(false)
const originalMemberships = ref({ ids: [], primary: null })
const departmentOptions = computed(() => {
  const byId = new Map(departments.value.map(department => [department.id, department]))
  return departments.value.map((department) => {
    const names = [department.name]
    let parent = byId.get(department.parentId)
    while (parent) {
      names.unshift(parent.name)
      parent = byId.get(parent.parentId)
    }
    return {
      value: department.id,
      label: names.join(' / '),
      enabled: department.enabled,
    }
  })
})
const activeDepartments = computed(() => departmentOptions.value.filter(department => department.enabled))
const departmentScopes = computed(() => [
  {
    value: 'ANY',
    label: t('任意归属'),
  },
  {
    value: 'PRIMARY',
    label: t('只匹配主部门'),
  },
])
const departmentFilters = computed(() => [
  {
    value: 'unassigned',
    label: t('未分配部门'),
  },
  ...departmentOptions.value,
])

function readUsers({ department, departmentScope, ...params }) {
  return api.read({
    ...params,
    departmentId: typeof department === 'number'
      ? department
      : undefined,
    departmentScope: typeof department === 'number'
      ? departmentScope ?? 'ANY'
      : undefined,
    unassigned: department === 'unassigned'
      ? true
      : undefined,
  })
}

async function refreshDepartments() {
  loadingDepartments.value = true
  try {
    departments.value = (await departmentApi.list()).data
    return departments.value
  }
  finally {
    loadingDepartments.value = false
  }
}

onMounted(() => {
  $table.value?.handleSearch()
  refreshRoles()
  if (userStore.currentRole.code === 'SUPER_ADMIN')
    refreshDepartments().catch(() => {})
})

const genders = computed(() => [
  {
    label: t('男'),
    value: 1,
  },
  {
    label: t('女'),
    value: 2,
  },
])
const roles = ref([])
const loadingRoles = ref(false)
const roleLoadFailed = ref(false)
const roleOptions = computed(() => roles.value.map(role => ({
  ...role,
  label: resourceLabel(role),
})))

async function refreshRoles() {
  if (loadingRoles.value)
    return
  loadingRoles.value = true
  roleLoadFailed.value = false
  try {
    const { data = [] } = await api.getAllRoles()
    roles.value = data
  }
  catch {
    roleLoadFailed.value = true
  }
  finally {
    loadingRoles.value = false
  }
}

const {
  modalRef,
  modalFormRef,
  modalForm,
  modalAction,
  okLoading,
  handleAdd,
  handleDelete,
  handleOpen,
  handleSave,
} = useCrud({
  name: '用户',
  initForm: { enable: true, departmentIds: [], primaryDepartmentId: null },
  async doCreate(form) {
    const { departmentIds, primaryDepartmentId, ...data } = form
    const response = await api.create({
      ...data,
      memberships: departmentIds.length
        ? { departmentIds, primaryDepartmentId }
        : undefined,
    })
    temporaryCredential.value = response.data
    return response
  },
  doDelete: api.delete,
  async doUpdate({ id, nameCn, nameEn, domainAccount, mobile, departmentIds, primaryDepartmentId, departmentVersion }) {
    const original = originalMemberships.value
    const changed = primaryDepartmentId !== original.primary || departmentIds.length !== original.ids.length
      || departmentIds.some(departmentId => !original.ids.includes(departmentId))
    try {
      const response = await api.update({
        id,
        nameCn,
        nameEn,
        domainAccount,
        mobile,
        memberships: changed
          ? { departmentIds, primaryDepartmentId, expectedVersion: departmentVersion }
          : undefined,
      })
      userStore.updateNameEn(id, nameEn)
      return response
    }
    catch (error) {
      if (error?.code === 409)
        departmentConflict.value = true
      throw error
    }
  },
  refresh: () => $table.value?.handleSearch(),
})

const primaryOptions = computed(() => activeDepartments.value.filter(department => modalForm.value.departmentIds?.includes(department.value)))
const primaryDepartmentRule = {
  required: true,
  trigger: 'change',
  validator: (_rule, value) => modalForm.value.departmentIds?.includes(value)
    || new Error(t('请从所属部门中选择主部门')),
}

const assignmentRoleOptions = computed(() => {
  const options = new Map(roleOptions.value.map(role => [role.id, role]))
  // 已有的停用角色也要可见，并允许在本次编辑中撤销移出。
  for (const role of modalForm.value.originalRoles ?? []) {
    if (!options.has(role.id)) {
      const label = resourceLabel(role)
      options.set(role.id, {
        id: role.id,
        label: role.enable === false
          ? `${label} (${t('停用')})`
          : label,
      })
    }
  }
  return Array.from(options.values())
})

const columns = computed(() => [
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
        size: isCompactTable.value ? 22 : appStore.tableSize,
        src: row.avatar,
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
    title: t('域账号'),
    key: 'domainAccount',
    width: 190,
    ellipsis: { tooltip: true },
  },
  {
    title: t('手机号'),
    key: 'mobile',
    width: 140,
    ellipsis: { tooltip: true },
  },
  {
    title: t('中文姓名'),
    key: 'nameCn',
    render: row => row.nameCn
      ? h(PersonLink, { userId: row.id, label: row.nameCn })
      : '—',
    width: 160,
    ellipsis: { tooltip: true },
  },
  {
    title: t('角色'),
    key: 'roles',
    width: 200,
    ellipsis: { tooltip: true },
    render: ({ roles }) => {
      if (roles?.length) {
        return roles.map((item, index) =>
          h(
            NTag,
            {
              type: 'success',
              style: index > 0
                ? 'margin-left: 8px;'
                : '',
            },
            {
              default: () => resourceLabel(item),
            },
          ),
        )
      }
      return t('暂无角色')
    },
  },
  {
    title: t('性别'),
    key: 'gender',
    width: 80,
    render: ({ gender }) => genders.value.find(item => gender === item.value)?.label ?? '',
  },
  {
    title: t('邮箱'),
    key: 'email',
    width: 150,
    ellipsis: { tooltip: true },
  },
  {
    title: t('创建时间'),
    key: 'createDate',
    width: 180,
    render(row) {
      return h('span', formatDateTime(row.createTime))
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
    render: row => row.departments?.filter(department => department.id !== row.departmentId).map(department => department.name).join('、') || '—',
  },
  {
    title: t('状态'),
    key: 'enable',
    width: 120,
    render: row =>
      h(
        NSwitch,
        {
          size: 'small',
          rubberBand: false,
          value: row.enable,
          loading: !!row.enableLoading,
          onUpdateValue: () => handleEnable(row),
        },
        {
          checked: () => t('启用'),
          unchecked: () => t('停用'),
        },
      ),
  },
  {
    title: t('操作'),
    key: 'actions',
    width: useActionsDropdown.value ? dropdownActionsWidth : inlineActionsWidth,
    align: 'right',
    fixed: 'right',
    hideInExcel: true,
    render(row) {
      const actions = [
        {
          key: 'edit',
          label: t('编辑'),
          onClick: () => openEdit(row),
        },
        {
          key: 'assign-roles',
          label: t('分配角色'),
          icon: 'i-carbon:user-role text-14',
          type: 'primary',
          secondary: true,
          onClick: () => handleOpenRolesSet(row),
        },
        {
          key: 'reset-password',
          label: t('重置密码'),
          icon: 'i-radix-icons:reset text-14',
          type: 'primary',
          onClick: () => handleOpen({
            action: 'reset',
            title: t('重置密码'),
            row,
            onOk: onSave,
          }),
        },
        {
          key: 'delete',
          label: t('删除'),
          icon: 'i-material-symbols:delete-outline text-14',
          type: 'error',
          onClick: () => confirmDelete(row),
        },
      ]
      return h(TableActions, {
        actions,
        dropdown: useActionsDropdown.value,
        size: isCompactTable.value ? 'tiny' : 'small',
      })
    },
  },
])
const tableScrollWidth = computed(() => columns.value.reduce((width, column) => width + column.width, 0))

function selectDepartments(ids) {
  modalForm.value.departmentIds = ids
  if (!ids.includes(modalForm.value.primaryDepartmentId)) {
    modalForm.value.primaryDepartmentId = ids.length === 1
      ? ids[0]
      : null
  }
}

function openCreate() {
  departmentConflict.value = false
  originalMemberships.value = { ids: [], primary: null }
  refreshDepartments().catch(() => {})
  handleAdd()
}

function openEdit(row) {
  const ids = row.departments.map(department => department.id)
  departmentConflict.value = false
  originalMemberships.value = { ids, primary: row.departmentId }
  refreshDepartments().catch(() => {})
  handleOpen({
    action: 'edit',
    title: t('编辑用户'),
    row: {
      id: row.id,
      username: row.username,
      nameCn: row.nameCn ?? '',
      nameEn: row.nameEn ?? '',
      domainAccount: row.domainAccount ?? '',
      mobile: row.mobile ?? '',
      departmentIds: ids,
      primaryDepartmentId: row.departmentId,
      departmentVersion: row.departmentVersion,
    },
    onOk: () => departmentConflict.value
      ? false
      : handleSave(),
  })
}

async function reloadMemberships() {
  const form = modalForm.value
  try {
    const [, { data }] = await Promise.all([
      refreshDepartments(),
      departmentApi.memberships(form.id),
    ])
    if (modalForm.value !== form)
      return
    const ids = data.departments.map(department => department.id)
    originalMemberships.value = { ids, primary: data.primaryDepartmentId }
    Object.assign(form, {
      departmentIds: [...ids],
      primaryDepartmentId: data.primaryDepartmentId,
      departmentVersion: data.departmentVersion,
    })
    departmentConflict.value = false
  }
  catch {
    // 接口拦截器展示错误。
  }
}

async function leadershipImpact(userId) {
  const led = (await refreshDepartments()).filter(department => department.leaderUserId === userId)
  const ledIds = new Set(led.map(department => department.id))
  return departmentOptions.value.filter(department => ledIds.has(department.value)).map(department => department.label).join('、')
}

async function confirmDelete(row) {
  try {
    const impact = await leadershipImpact(row.id)
    handleDelete(row.id, impact
      ? { content: `${t('确定删除？')} ${t('以下部门的负责人将清空：{0}', impact)}` }
      : undefined)
  }
  catch {
    // 取得影响范围后才显示确认。
  }
}

async function handleEnable(row) {
  if (row.enable) {
    try {
      const impact = await leadershipImpact(row.id)
      if (impact) {
        $dialog.warning({
          title: t('停用用户'),
          content: t('以下部门的负责人将清空：{0}', impact),
          positiveText: t('确定'),
          negativeText: t('取消'),
          onPositiveClick: () => saveEnabled(row),
        })
        return
      }
    }
    catch {
      return
    }
  }
  await saveEnabled(row)
}

async function saveEnabled(row) {
  row.enableLoading = true
  try {
    await api.update({ id: row.id, enable: !row.enable })
    row.enableLoading = false
    $message.success(t('操作成功'))
    $table.value?.handleSearch()
  }
  catch (error) {
    console.error(error)
    row.enableLoading = false
  }
}

function handleOpenRolesSet(row) {
  const originalRoles = row.roles ?? []
  refreshRoles()
  handleOpen({
    action: 'setRole',
    title: t('分配角色'),
    width: 'min(760px, calc(100vw - 24px))',
    row: {
      id: row.id,
      username: row.username,
      roleIds: originalRoles.map(role => role.id),
      originalRoles,
    },
    onOk: onSave,
  })
}

function onSave() {
  if (modalAction.value === 'setRole') {
    if (loadingRoles.value || roleLoadFailed.value || okLoading.value)
      return false
    const { id, roleIds } = modalForm.value
    return handleSave({
      api: () => api.update({ id, roleIds }),
      cb: () => $message.success(t('分配成功')),
    })
  }
  else if (modalAction.value === 'reset') {
    return handleSave({
      async api() {
        const response = await api.resetPwd(modalForm.value.id)
        temporaryCredential.value = response.data
        return response
      },
      cb: () => $message.success(t('密码重置成功')),
    })
  }
  handleSave()
}
</script>
