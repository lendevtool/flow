<template>
  <CommonPage>
    <template #action>
      <NButton type="primary" @click="handleAdd()">
        <i class="i-material-symbols:add mr-4 text-18" />
        {{ t('新增角色') }}
      </NButton>
    </template>

    <MeCrud
      ref="$table"
      v-model:query-items="queryItems"
      :scroll-x="1200"
      :columns="columns"
      :get-data="api.read"
    >
      <MeQueryItem :label="t('角色名')" :label-width="50">
        <n-input v-model:value="queryItems.name" type="text" :placeholder="t('请输入角色名')" clearable />
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
    </MeCrud>
    <MeModal ref="modalRef" width="520px">
      <n-form
        ref="modalFormRef"
        label-placement="left"
        label-align="left"
        :label-width="105"
        :model="modalForm"
      >
        <n-form-item
          :label="t('角色名')"
          path="name"
          :rule="{
            required: true,
            message: t('请输入角色名'),
            trigger: ['input', 'blur'],
          }"
        >
          <n-input v-model:value="modalForm.name" />
        </n-form-item>
        <n-form-item :label="t('英文名称')" path="nameEn">
          <n-input v-model:value="modalForm.nameEn" :maxlength="100" :placeholder="t('选填')" />
        </n-form-item>
        <n-form-item
          :label="t('角色编码')"
          path="code"
          :rule="{
            required: true,
            message: t('请输入角色编码'),
            trigger: ['input', 'blur'],
          }"
        >
          <n-input v-model:value="modalForm.code" :disabled="modalAction !== 'add'" />
        </n-form-item>
        <n-form-item :label="t('权限')" path="permissionIds">
          <n-tree
            key-field="id"
            :render-label="({ option }) => resourceLabel(option)"
            :selectable="false"
            :data="permissionTree"
            :checked-keys="modalForm.permissionIds"
            :on-update:checked-keys="(keys) => (modalForm.permissionIds = keys)"

            checkable check-on-click default-expand-all
            class="cus-scroll max-h-200 w-full"
          />
        </n-form-item>
        <n-form-item :label="t('状态')" path="enable">
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
    </MeModal>
  </CommonPage>
</template>

<script setup>
import { NButton, NSwitch } from 'naive-ui'
import { MeCrud, MeModal, MeQueryItem } from '@/components'
import TableActions from '@/components/common/TableActions.vue'
import { useCrud } from '@/composables'
import { t } from '@/i18n'
import { resourceLabel } from '@/i18n/navigation'
import { useUserStore } from '@/store'
import api from './api'

defineOptions({ name: 'RoleMgt' })

const router = useRouter()
const userStore = useUserStore()

const $table = ref(null)
/** QueryBar筛选参数（可选） */
const queryItems = ref({})

onMounted(() => {
  $table.value?.handleSearch()
})

const { modalRef, modalFormRef, modalAction, modalForm, handleAdd, handleDelete, handleEdit }
  = useCrud({
    name: '角色',
    doCreate: api.create,
    doDelete: api.delete,
    async doUpdate(data) {
      const response = await api.update(data)
      userStore.updateRoleNames(data)
      return response
    },
    initForm: { enable: true },
    refresh: (_, keepCurrentPage) => $table.value?.handleSearch(keepCurrentPage),
  })

const columns = computed(() => [
  {
    title: t('角色名'),
    key: 'name',
    render: resourceLabel,
  },
  {
    title: t('角色编码'),
    key: 'code',
  },
  {
    title: t('状态'),
    key: 'enable',
    render: row =>
      h(
        NSwitch,
        {
          size: 'small',
          rubberBand: false,
          value: row.enable,
          loading: !!row.enableLoading,
          disabled: row.code === 'SUPER_ADMIN',
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
    width: 320,
    align: 'right',
    fixed: 'right',
    render: row => h(TableActions, {
      actions: [
        {
          key: 'assign-users',
          label: t('分配用户'),
          icon: 'i-fe:user-plus text-14',
          type: 'primary',
          secondary: true,
          onClick: () => router.push({
            name: 'RoleUserTransfer',
            params: { roleId: row.id },
            query: { roleName: row.name, roleNameEn: row.nameEn, roleCode: row.code },
          }),
        },
        {
          key: 'edit',
          label: t('编辑'),
          icon: 'i-material-symbols:edit-outline text-14',
          type: 'primary',
          disabled: row.code === 'SUPER_ADMIN',
          onClick: () => handleEdit(row),
        },
        {
          key: 'delete',
          label: t('删除'),
          icon: 'i-material-symbols:delete-outline text-14',
          type: 'error',
          disabled: row.code === 'SUPER_ADMIN',
          onClick: () => handleDelete(row.id),
        },
      ].filter(action => action.key !== 'assign-users' || router.hasRoute('RoleUserTransfer')),
    }),
  },
])

async function handleEnable(row) {
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

const permissionTree = ref([])
api.getAllPermissionTree().then(({ data = [] }) => (permissionTree.value = data))
</script>
