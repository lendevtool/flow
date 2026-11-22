<template>
  <n-modal :show="true" preset="card" :title="t('选择部门')" style="width: min(620px, 94vw)" @update:show="close">
    <n-space vertical :size="12">
      <n-input v-model:value="pattern" clearable :input-props="{ 'aria-label': t('搜索部门名称') }" :placeholder="t('搜索部门名称')" />
      <n-alert v-if="error" type="error">
        {{ errorMessage(error, t('加载失败')) }}
        <n-button text @click="reload">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-spin v-else :show="loading">
        <n-scrollbar v-if="options.roots.length" style="max-height: 350px">
          <n-tree
            :data="options.roots"
            :pattern="pattern.trim()"
            :checked-keys="checked"
            :cascade="false"
            checkable
            block-line
            default-expand-all
            @update:checked-keys="updateChecked"
          />
        </n-scrollbar>
        <n-empty v-else-if="!loading" :description="t('暂无可选部门')" />
      </n-spin>
    </n-space>
    <template #footer>
      <n-space justify="end">
        <n-button @click="emit('close')">
          {{ t('取消') }}
        </n-button>
        <n-button type="primary" :disabled="!checked.length" @click="addDepartments">
          {{ t('添加所选（{0}）', checked.length) }}
        </n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import type { DepartmentSource } from './api'
import { computed, ref } from 'vue'
import { t } from '@/i18n'
import api from './api'
import { departmentTree, errorMessage } from './model'
import { useLatestResource } from './useLatestResource'

const props = defineProps<{ excluded: ReadonlySet<number>, limit: number }>()
const emit = defineEmits<{ select: [departments: DepartmentSource[]], close: [] }>()

const pattern = ref('')
const checked = ref<number[]>([])
const { data, loading, error, reload } = useLatestResource(() => async signal => (await api.departmentOptions(signal)).data)
const options = computed(() => departmentTree(data.value ?? [], props.excluded))

function updateChecked(keys: Array<string | number>) {
  if (keys.length > props.limit) {
    window.$message.warning(t('本次还可添加 {0} 项。', props.limit))
    return
  }
  checked.value = keys.filter((key): key is number => typeof key === 'number' && !props.excluded.has(key))
}

function addDepartments() {
  emit('select', checked.value.map(departmentId => ({
    departmentId,
    path: options.value.paths.get(departmentId) ?? String(departmentId),
    status: 'ACTIVE',
    includeDescendants: false,
  })))
}

function close(show: boolean) {
  if (!show)
    emit('close')
}
</script>
