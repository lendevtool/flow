<template>
  <div>
    <n-space vertical :size="12">
      <div class="flex items-center justify-between">
        <h3>{{ t('菜单') }}</h3>
        <NButton v-if="canSort && treeData.length" size="small" secondary type="info" :disabled="sortingDisabled" @click="emit('sort')">
          <i class="i-fe:shuffle mr-4 text-14" />
          {{ t('调整排序') }}
        </NButton>
      </div>
      <div class="flex">
        <n-input v-model:value="pattern" :placeholder="t('搜索')" clearable />
        <NButton class="ml-12" type="primary" @click="handleAdd()">
          <i class="i-material-symbols:add mr-4 text-14" />
          {{ t('新增') }}
        </NButton>
      </div>

      <n-tree
        :show-irrelevant-nodes="false"
        :pattern="pattern"
        :data="treeData"
        :render-label="({ option }) => resourceLabel(option)"
        :filter="matchesName"
        :selected-keys="[currentMenu?.code]"
        :render-prefix="renderPrefix"
        :render-suffix="renderSuffix"
        :on-update:selected-keys="onSelect"
        key-field="code"
        label-field="name"

        block-line default-expand-all
      />
    </n-space>

    <ResAddOrEdit ref="modalRef" :menus="treeData" @refresh="(data) => emit('refresh', data)" />
  </div>
</template>

<script setup>
import { NButton } from 'naive-ui'
import { withModifiers } from 'vue'
import { t } from '@/i18n'
import { resourceLabel } from '@/i18n/navigation'
import api from '../api'
import ResAddOrEdit from './ResAddOrEdit.vue'

defineProps({
  treeData: {
    type: Array,
    default: () => [],
  },
  currentMenu: {
    type: Object,
    default: () => null,
  },
  canSort: Boolean,
  sortingDisabled: Boolean,
})
const emit = defineEmits(['refresh', 'sort', 'update:currentMenu'])

const pattern = ref('')

function matchesName(pattern, item) {
  const keyword = pattern.trim().toLowerCase()
  return [
    item.name,
    item.nameEn,
    resourceLabel(item),
  ]
    .some(name => name?.toLowerCase().includes(keyword))
}

const modalRef = ref(null)
async function handleAdd(data = {}) {
  modalRef.value?.handleOpen({
    action: 'add',
    title: t('新增菜单'),
    row: { type: 'MENU', ...data },
    okText: t('保存'),
  })
}

function onSelect(keys, option, { action, node }) {
  emit('update:currentMenu', action === 'select'
    ? node
    : null)
}

function renderPrefix({ option }) {
  return h('i', { class: `${option.icon}?mask text-16` })
}

function renderSuffix({ option }) {
  return [
    h(
      NButton,
      {
        text: true,
        type: 'primary',
        title: t('新增下级菜单'),
        size: 'tiny',
        onClick: withModifiers(() => handleAdd({ parentId: option.id }), ['stop']),
      },
      {
        default: () => t('新增'),
      },
    ),

    h(
      NButton,
      {
        text: true,
        type: 'error',
        size: 'tiny',
        style: 'margin-left: 12px;',
        onClick: withModifiers(() => handleDelete(option), ['stop']),
      },
      {
        default: () => t('删除'),
      },
    ),
  ]
}

function handleDelete(item) {
  $dialog.confirm({
    content: t('确认删除【{0}】？', resourceLabel(item)),
    async confirm() {
      try {
        $message.loading(t('正在删除'), { key: 'deleteMenu' })
        await api.deletePermission(item.id)
        $message.success(t('删除成功'), { key: 'deleteMenu' })
        emit('refresh')
        emit('update:currentMenu', null)
      }
      catch (error) {
        console.error(error)
        $message.destroy('deleteMenu')
      }
    },
  })
}
</script>
