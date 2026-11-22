<template>
  <n-modal :show="true" preset="card" :title="t('管理待办')" class="max-w-95vw w-560" :closable="!saving" :mask-closable="false" @close="emit('close')" @esc="!saving && emit('close')">
    <n-form inline :show-feedback="false" @submit.prevent="loadTodo">
      <n-form-item :label="t('待办编号')">
        <n-input-number v-model:value="id" :min="1" :precision="0" :disabled="saving" />
      </n-form-item>
      <n-form-item>
        <n-button attr-type="submit" :disabled="!id || saving" :loading="record.loading.value">
          {{ t('查询') }}
        </n-button>
      </n-form-item>
    </n-form>
    <n-alert v-if="error || record.error.value || people.error.value" type="error" class="mb-12">
      {{ error || record.error.value || people.error.value }}
    </n-alert>
    <template v-if="record.data.value">
      <n-descriptions :column="2" bordered size="small" class="mb-12">
        <n-descriptions-item :label="t('待办事项')">
          {{ record.data.value.title }}
        </n-descriptions-item>
        <n-descriptions-item :label="t('当前负责人')">
          {{ record.data.value.assigneeName }}
        </n-descriptions-item>
      </n-descriptions>
      <template v-if="canRecover">
        <n-form-item :label="t('新负责人')">
          <n-select v-model:value="assignee" :options="options" :loading="people.loading.value" :disabled="saving" filterable />
        </n-form-item>
        <n-space justify="end">
          <n-button type="primary" :loading="saving" :disabled="!assignee" @click="assign">
            {{ t('改派') }}
          </n-button>
        </n-space>
      </template>
      <n-alert v-else type="info">
        {{ t('仅可改派未关闭的普通或周期待办。流程节点请通过流程处理。') }}
      </n-alert>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import type { Assignee, Todo } from './api'
import { computed, onMounted, ref } from 'vue'
import { t } from '@/i18n'
import api from './api'
import { workbenchError } from './model'
import { useWorkbenchRequest } from './useWorkbench'

const emit = defineEmits<{ close: [], saved: [] }>()
const id = ref<number | null>(null)
const assignee = ref<number | null>(null)
const error = ref('')
const saving = ref(false)
const record = useWorkbenchRequest<Todo | null>(null)
const people = useWorkbenchRequest<Assignee[]>([])
const options = computed(() => people.data.value.map(person => ({ value: person.id, label: person.name })))
const canRecover = computed(() => {
  const todo = record.data.value
  return todo && todo.origin !== 'FLOW' && (todo.status === 'OPEN' || todo.status === 'IN_PROGRESS')
})
onMounted(() => people.run(api.assignees))

async function loadTodo() {
  const number = id.value
  if (!number || !Number.isSafeInteger(number))
    return
  error.value = ''
  assignee.value = null
  record.reset()
  await record.run(() => api.managedDetail(number))
}

async function assign() {
  const todo = record.data.value
  if (!todo || !assignee.value || saving.value)
    return
  saving.value = true
  error.value = ''
  try {
    const { data } = await api.managedAssign(todo.id, todo.version, assignee.value)
    record.data.value = data
    assignee.value = null
    emit('saved')
  }
  catch (cause) {
    error.value = workbenchError(cause, t('保存失败，请重试'))
  }
  finally {
    saving.value = false
  }
}
</script>
