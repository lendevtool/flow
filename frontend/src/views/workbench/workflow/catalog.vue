<template>
  <CommonPage>
    <WorkflowPage v-if="template" :key="template.id" mode="start" :definition="template" @started="openWorkflow" @close="template = undefined" />
    <div class="h-full flex flex-col">
      <n-alert v-if="listFailed" type="error" class="mb-12 shrink-0">
        {{ t('加载失败') }}
        <n-button text @click="table?.handleSearch(true)">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <MeCrud
        ref="table"
        :columns="columns"
        :get-data="api.catalog"
        :page-size="20"
        :scroll-x="520"
        class="min-h-0 flex-1"
        @update:query-failed="listFailed = $event"
      >
        <template #empty="{ loading }">
          <n-empty v-if="!loading && !listFailed" :description="t('暂无可发起的流程')" />
        </template>
      </MeCrud>
    </div>
  </CommonPage>
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { Detail } from './api'
import type { WorkflowDefinition } from '@/views/pms/workflow/api'
import { computed, h, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import TableActions from '@/components/common/TableActions.vue'
import MeCrud from '@/components/me/crud/index.vue'
import { t } from '@/i18n'
import api from './api'
import WorkflowPage from './WorkflowPage.vue'

defineOptions({ name: 'WorkflowCatalog' })

const router = useRouter()
const table = ref<InstanceType<typeof MeCrud> | null>(null)
const template = ref<WorkflowDefinition>()
const listFailed = ref(false)

const columns = computed<DataTableColumns<WorkflowDefinition>>(() => [
  {
    title: t('流程模板'),
    key: 'name',
    minWidth: 200,
    ellipsis: { tooltip: true },
  },
  {
    title: t('模板版本'),
    key: 'revision',
    width: 120,
  },
  {
    title: t('操作'),
    key: 'actions',
    width: 176,
    align: 'right',
    fixed: 'right',
    render: row => h(TableActions, {
      actions: [
        {
          key: 'start',
          label: t('发起流程'),
          icon: 'i-fe:send text-14',
          type: 'primary',
          onClick: () => { template.value = row },
        },
      ],
    }),
  },
])

onMounted(() => table.value?.handleSearch())
async function openWorkflow(detail: Detail) {
  const task = detail.tasks.find(item => item.canSubmit)
  await router.push({
    path: `/workbench/workflow-task/${detail.instance.id}`,
    query: {
      mode: task ? 'task' : undefined,
      todo: task?.todoId,
    },
  })
  template.value = undefined
}
</script>
