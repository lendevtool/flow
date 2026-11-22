<template>
  <CommonPage back>
    <template #title-suffix>
      <NTag class="ml-12" type="warning">
        {{ roleName }}
      </NTag>
    </template>
    <template #action>
      <div class="flex items-center">
        <NButton class="mr-12" @click="showHandlers = true">
          {{ t('流程主备') }}
        </NButton>
        <NButton :disabled="!userIds.length" type="error" @click="handleBatchRemove()">
          <i v-if="userIds.length" class="i-material-symbols:delete-outline mr-4 text-18" />
          {{ t('批量取消授权') }}
        </NButton>
        <NButton
          class="ml-12"
          :disabled="!userIds.length"
          type="primary"
          @click="handleBatchAdd()"
        >
          <i v-if="userIds.length" class="i-line-md:confirm-circle mr-4 text-18" />
          {{ t('批量授权') }}
        </NButton>
      </div>
    </template>

    <MeCrud
      ref="$table"
      v-model:query-items="queryItems"
      :scroll-x="1200"
      :columns="columns"
      :get-data="api.getAllUsers"
      @on-checked="onChecked"
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
    </MeCrud>
    <WorkflowHandlers v-if="showHandlers" :role-id="Number(route.params.roleId)" @close="showHandlers = false" />
  </CommonPage>
</template>

<script setup>
import { NButton, NSwitch, NTag } from 'naive-ui'
import { h } from 'vue'
import { MeCrud, MeQueryItem } from '@/components'
import AccountAvatar from '@/components/common/AccountAvatar.vue'
import PersonLink from '@/components/common/PersonLink.vue'
import TableActions from '@/components/common/TableActions.vue'
import { t } from '@/i18n'
import { userDisplayName } from '@/i18n/names'
import { navigationLabel, resourceLabel } from '@/i18n/navigation'
import { formatDateTime } from '@/utils'
import api from './api'
import WorkflowHandlers from './WorkflowHandlers.vue'

defineOptions({ name: 'RoleUser' })
const route = useRoute()
const showHandlers = ref(false)
const roleName = computed(() => navigationLabel(route.query.roleCode, route.query.roleName, route.query.roleNameEn))

const $table = ref(null)
/** QueryBar筛选参数（可选） */
const queryItems = ref({})

onMounted(() => {
  $table.value?.handleSearch()
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

const columns = computed(() => [
  { type: 'selection', fixed: 'left' },
  {
    title: t('头像'),
    key: 'avatar',
    width: 80,
    render: row => h(PersonLink, {
      userId: row.id,
      label: row.username,
      ariaLabel: t('查看 {0} 的人员信息', row.username),
    }, {
      default: () => h(AccountAvatar, { size: 'medium', src: row.avatar }),
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
    title: t('显示名称'),
    key: 'displayName',
    width: 150,
    ellipsis: { tooltip: true },
    render: row => h(PersonLink, {
      userId: row.id,
      label: userDisplayName(row),
    }),
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
    title: t('创建时间'),
    key: 'createDate',
    width: 180,
    render(row) {
      return h('span', formatDateTime(row.createTime))
    },
  },
  {
    title: t('状态'),
    key: 'enable',
    width: 100,

    render: row =>
      h(
        NSwitch,
        {
          size: 'small',
          rubberBand: false,
          value: row.enable,
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
    width: 100,
    align: 'right',
    fixed: 'right',
    hideInExcel: true,
    render(row) {
      const assigned = row.roles?.some(item => item.id === +route.params.roleId)
      return h(TableActions, {
        actions: [
          {
            key: assigned ? 'revoke' : 'authorize',
            label: assigned
              ? t('取消授权')
              : t('授权'),
            icon: assigned ? 'i-material-symbols:delete-outline text-14' : 'i-line-md:confirm-circle text-14',
            type: assigned ? 'error' : 'primary',
            secondary: true,
            onClick: () => assigned
              ? handleBatchRemove([row.id])
              : handleBatchAdd([row.id]),
          },
        ],
      })
    },
  },
])

const userIds = ref([])
function onChecked(rowKeys) {
  userIds.value = rowKeys || []
}

function handleBatchAdd(ids = userIds.value) {
  const roleId = route.params.roleId
  if (!roleId)
    return $message.error(t('角色异常，请重新选择角色'))
  if (!ids.length)
    return $message.error(t('请先选择用户'))
  $dialog.confirm({
    content: t('确认分配【{0}】？', roleName.value),
    async confirm() {
      await api.addRoleUsers(roleId, { userIds: ids })
      $table.value?.handleSearch()
    },
  })
}
function handleBatchRemove(ids = userIds.value) {
  const roleId = route.params.roleId
  if (!roleId)
    return $message.error(t('角色异常，请重新选择角色'))
  if (!ids.length)
    return $message.error(t('请先选择用户'))
  $dialog.confirm({
    content: t('确认取消分配【{0}】？', roleName.value),
    async confirm() {
      await api.removeRoleUsers(roleId, { userIds: ids })
      $table.value?.handleSearch()
    },
  })
}
</script>
