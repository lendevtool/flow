<template>
  <n-drawer :show="true" :width="540" style="max-width: 100vw" @update:show="show => !show && emit('close')">
    <n-drawer-content :title="t('待办详情')" closable>
      <n-alert v-if="detail.error.value" type="error" class="mb-12">
        {{ detail.error.value }}
        <n-button text @click="refresh">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-spin :show="detail.loading.value">
        <template v-if="todo">
          <h2 class="mb-8 break-words text-20 font-semibold">
            {{ todo.title }}
          </h2>
          <n-space class="mb-16">
            <n-tag :type="todo.status === 'DONE' ? 'success' : 'default'">
              {{ t(statusLabels[todo.status]) }}
            </n-tag>
            <n-tag v-if="todo.overdue" type="warning">
              {{ t('逾期') }}
            </n-tag>
          </n-space>
          <TodoDetailDescriptions :todo="todo">
            <template #before>
              <n-descriptions-item v-if="todo.recordLabel" :label="t('所属单据')">
                {{ todo.recordLabel }}
              </n-descriptions-item>
            </template>
            <n-descriptions-item v-if="todo.status === 'DONE'" :label="t('实际完成时间')">
              {{ completionTime }}
            </n-descriptions-item>
          </TodoDetailDescriptions>
        </template>
      </n-spin>
      <template v-if="businessUrl || workUrl" #footer>
        <n-space>
          <RouterLink v-if="businessUrl" :to="businessUrl">{{ t('查看业务详情') }}</RouterLink>
          <RouterLink v-if="workUrl" :to="workUrl">{{ t('打开办理') }}</RouterLink>
        </n-space>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { BusinessTodoDetail } from './api'
import { computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { language, t } from '@/i18n'
import { internalNavigationPath } from '@/utils/navigationTarget'
import { statusLabels } from '../model'
import TodoDetailDescriptions from '../TodoDetailDescriptions.vue'
import { useWorkbenchRequest } from '../useWorkbench'

const props = defineProps<{ load: () => Promise<{ data: BusinessTodoDetail }> }>()
const emit = defineEmits<{ close: [] }>()
const detail = useWorkbenchRequest<BusinessTodoDetail | null>(null)
const todo = detail.data
const businessUrl = computed(() => internalNavigationPath(todo.value?.businessUrl))
const workUrl = computed(() => internalNavigationPath(todo.value?.workUrl))
const completionTime = computed(() => {
  const timestamp = todo.value?.closedAt
  if (!timestamp)
    return '—'
  return new Intl.DateTimeFormat(language.value, { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Shanghai' }).format(new Date(timestamp))
})

function refresh() {
  detail.reset()
  return detail.run(props.load)
}

onMounted(refresh)
</script>
