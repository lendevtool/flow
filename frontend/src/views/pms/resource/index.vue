<template>
  <CommonPage>
    <div class="flex">
      <n-spin size="small" :show="treeLoading">
        <MenuTree
          v-model:current-menu="currentMenu"
          class="w-320 shrink-0"
          :tree-data="treeData"
          :can-sort="canSort"
          :sorting-disabled="treeLoading || savingOrder"
          @refresh="initData"
          @sort="openSort()"
        />
      </n-spin>

      <div class="ml-40 w-0 flex-1">
        <template v-if="currentMenu">
          <div class="flex justify-between">
            <h3 class="mb-12">
              {{ resourceLabel(currentMenu) }}
            </h3>
            <div class="flex gap-8">
              <NButton v-if="canSort && selectedChildren.length" size="small" secondary type="info" :disabled="treeLoading || savingOrder" @click="openSort(currentMenu)">
                <i class="i-fe:shuffle mr-4 text-14" />
                {{ t('调整排序') }}
              </NButton>
              <NButton size="small" type="primary" @click="handleEdit(currentMenu)">
                <i class="i-material-symbols:edit-outline mr-4 text-14" />
                {{ t('编辑') }}
              </NButton>
            </div>
          </div>
          <n-descriptions label-placement="left" bordered :column="2">
            <n-descriptions-item :label="t('编码')">
              {{ currentMenu.code }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('名称')">
              {{ resourceLabel(currentMenu) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('路由地址')">
              {{ currentMenu.path ?? '--' }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('组件路径')">
              {{ currentMenu.component ?? '--' }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('菜单图标')">
              <span v-if="currentMenu.icon" class="flex items-center">
                <i :class="`${currentMenu.icon}?mask text-22 mr-8`" />
                <span class="opacity-50">{{ currentMenu.icon }}</span>
              </span>
              <span v-else>{{ t('无') }}</span>
            </n-descriptions-item>
            <n-descriptions-item label="layout">
              {{ currentMenu.layout || t('跟随系统') }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('是否显示')">
              {{ currentMenu.show ? t('是') : t('否') }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('是否启用')">
              {{ currentMenu.enable ? t('是') : t('否') }}
            </n-descriptions-item>
            <n-descriptions-item label="KeepAlive">
              {{ currentMenu.keepAlive ? t('是') : t('否') }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('排序')">
              {{ currentMenu.order ?? '--' }}
            </n-descriptions-item>
          </n-descriptions>

          <div class="mt-32 flex justify-between">
            <h3 class="mb-12">
              {{ t('按钮') }}
            </h3>
            <NButton size="small" type="primary" @click="handleAddBtn">
              <i class="i-fe:plus mr-4 text-14" />
              {{ t('新增') }}
            </NButton>
          </div>

          <MeCrud
            ref="$table"
            :key="currentMenu.id"
            :columns="btnsColumns"
            :scroll-x="-1"
            :get-data="api.getButtons"
            :query-items="{ parentId: currentMenu.id }"
          />
        </template>
        <n-empty v-else class="h-450 f-c-c" size="large" :description="t('请选择菜单查看详情')" />
      </div>
    </div>
    <ResAddOrEdit ref="modalRef" :menus="treeData" @refresh="initData" />
    <OrderEditor
      v-model:show="showOrder"
      v-model:rows="sortRows"
      :title="t('调整排序 · {0}', sortTitle)"
      :description="t('拖动{0}或使用箭头调整顺序，保存后按当前顺序重新编号。', sortParentId === null ? t('菜单') : t('资源'))"
      :saving="savingOrder"
      @save="saveOrder"
    />
  </CommonPage>
</template>

<script setup>
import { NButton, NSwitch } from 'naive-ui'
import { MeCrud } from '@/components'
import OrderEditor from '@/components/common/OrderEditor.vue'
import TableActions from '@/components/common/TableActions.vue'
import { t } from '@/i18n'
import { resourceLabel } from '@/i18n/navigation'
import { usePermissionStore, useUserStore } from '@/store'
import api from './api'
import MenuTree from './components/MenuTree.vue'
import ResAddOrEdit from './components/ResAddOrEdit.vue'

const permissions = usePermissionStore()
const user = useUserStore()
const canSort = computed(() => user.currentRole.code === 'SUPER_ADMIN')
const resourceTree = ref([])
const treeData = computed(() => menuNodes(resourceTree.value))
const treeLoading = ref(false)
const $table = ref(null)
const currentMenu = ref(null)
const selectedChildren = computed(() => currentMenu.value
  ? findResource(resourceTree.value, currentMenu.value.id)?.children?.filter(row => row.type === 'MENU' || row.type === 'BUTTON') ?? []
  : [])
let treeRequest = 0

function menuNodes(resources) {
  return resources.filter(row => row.type === 'MENU').map(row => ({
    ...row,
    children: menuNodes(row.children || []),
  }))
}

function findResource(resources, id) {
  for (const resource of resources) {
    if (resource.id === id)
      return resource
    const child = findResource(resource.children || [], id)
    if (child)
      return child
  }
  return null
}

async function initData(data) {
  const buttonChanged = data?.type === 'BUTTON'
  if (data?.id && !buttonChanged)
    permissions.updateResourceNames(data)
  const requestId = ++treeRequest
  treeLoading.value = true
  try {
    const res = await api.getPermissionTree()
    if (requestId !== treeRequest)
      return
    resourceTree.value = res?.data || []
    currentMenu.value = findResource(treeData.value, (buttonChanged ? null : data?.id) ?? currentMenu.value?.id)
    if (buttonChanged)
      await $table.value?.handleSearch(true)
  }
  catch { /* Request client displays read errors. */ }
  finally {
    if (requestId === treeRequest)
      treeLoading.value = false
  }
}
initData()

const showOrder = ref(false)
const savingOrder = ref(false)
const sortParentId = ref(null)
const sortTitle = ref('')
const sortRows = ref([])

function openSort(menu = null) {
  sortParentId.value = menu?.id ?? null
  sortTitle.value = menu
    ? resourceLabel(menu)
    : t('根菜单')
  const rows = menu ? selectedChildren.value : treeData.value
  sortRows.value = rows.map(row => ({
    id: row.id,
    label: resourceLabel(row),
    detail: menu
      ? `${row.code} · ${t(row.type === 'MENU'
        ? '菜单'
        : '按钮')}`
      : row.code,
  }))
  showOrder.value = true
}

async function saveOrder() {
  if (savingOrder.value)
    return
  savingOrder.value = true
  try {
    const parentId = sortParentId.value
    const ids = sortRows.value.map(row => row.id)
    await api.sortPermissions(parentId, ids)
    permissions.updateResourceOrder(ids)
    showOrder.value = false
    $message.success(t('排序已保存'))
    await initData()
    if (parentId === currentMenu.value?.id)
      await $table.value?.handleSearch(true)
  }
  catch { /* Request client displays write errors. */ }
  finally { savingOrder.value = false }
}

const modalRef = ref(null)
function handleEdit(item = {}) {
  modalRef.value?.handleOpen({
    action: 'edit',
    title: t('编辑菜单 - {0}', resourceLabel(item)),
    row: item,
    okText: t('保存'),
  })
}

const btnsColumns = computed(() => [
  {
    title: t('名称'),
    key: 'name',
    render: resourceLabel,
  },
  {
    title: t('编码'),
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
          key: 'edit',
          label: t('编辑'),
          icon: 'i-material-symbols:edit-outline text-14',
          type: 'primary',
          onClick: () => handleEditBtn(row),
        },
        {
          key: 'delete',
          label: t('删除'),
          icon: 'i-material-symbols:delete-outline text-14',
          type: 'error',
          onClick: () => handleDeleteBtn(row.id),
        },
      ],
    }),
  },
])

watch(
  () => currentMenu.value?.id,
  (id) => {
    if (id)
      $table.value?.handleSearch()
  },
  { flush: 'post' },
)

function handleAddBtn() {
  modalRef.value?.handleOpen({
    action: 'add',
    title: t('新增按钮'),
    row: { type: 'BUTTON', parentId: currentMenu.value.id },
    okText: t('保存'),
  })
}

function handleEditBtn(row) {
  modalRef.value?.handleOpen({
    action: 'edit',
    title: t('编辑按钮 - {0}', resourceLabel(row)),
    row,
    okText: t('保存'),
  })
}

function handleDeleteBtn(id) {
  const d = $dialog.warning({
    content: t('确定删除？'),
    title: t('提示'),
    positiveText: t('确定'),
    negativeText: t('取消'),
    async onPositiveClick() {
      try {
        d.loading = true
        await api.deletePermission(id)
        $message.success(t('删除成功'))
        await initData({ type: 'BUTTON' })
        d.loading = false
      }
      catch (error) {
        console.error(error)
        d.loading = false
      }
    },
  })
}

async function handleEnable(item) {
  try {
    item.enableLoading = true
    await api.savePermission(item.id, {
      enable: !item.enable,
    })
    $message.success(t('操作成功'))
    $table.value?.handleSearch()
    item.enableLoading = false
  }
  catch (error) {
    console.error(error)
    item.enableLoading = false
  }
}
</script>
