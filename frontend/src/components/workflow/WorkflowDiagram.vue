<template>
  <n-card size="small" :title="t('流程图')">
    <div class="flow-scroll" role="region" :aria-label="t('流程图')" tabindex="0">
      <ol class="flow-stages">
        <li
          v-for="(stage, index) in stages"
          :key="stage.key"
          class="flow-stage"
          :aria-current="stage.current ? 'step' : undefined"
        >
          <i v-if="index > 0" class="flow-arrow i-fe:arrow-right" aria-hidden="true" />
          <div class="flow-stage-body" :class="{ 'is-current': stage.current }">
            <div v-for="(node, nodeIndex) in stage.nodes" :key="node.key" :class="{ 'branch-node': nodeIndex > 0 }">
              <div class="flow-node" :data-node="node.key" :data-status="node.status">
                <div class="node-heading">
                  <strong>{{ node.title }}</strong>
                  <n-tag size="small" :type="statusTypes[node.status] ?? 'default'">
                    {{ t(statusLabels[node.status]) }}
                  </n-tag>
                </div>
                <div v-if="node.processedNames.length" class="node-people">
                  {{ t('已处理人') }} · {{ node.processedNames.join('、') }}
                </div>
                <div v-if="node.assigneeNames.length" class="node-people">
                  {{ t('待处理人') }} · {{ node.assigneeNames.join('、') }}
                </div>
                <div v-else-if="node.plannedNames.length" class="node-people">
                  {{ t('预计处理人') }} · {{ node.plannedNames.join('、') }}
                </div>
                <div v-else-if="node.roleName && (node.status === 'WAITING' || node.status === 'WAITING_ASSIGNMENT')" class="node-people">
                  {{ t('处理角色') }} · {{ node.roleName }}
                </div>
                <div v-else-if="nodeIndex === 0 && stage.nodes.length > 1" class="node-people">
                  {{ t('并行处理，自动汇总') }}
                </div>
              </div>
            </div>
          </div>
        </li>
      </ol>
    </div>
  </n-card>
</template>

<script setup lang="ts">
import type { WorkflowDefinition } from '@/views/pms/workflow/api'
import type { AttemptStatus, Detail } from '@/views/workbench/workflow/api'
import { computed } from 'vue'
import { t } from '@/i18n'
import { handlingRoles } from '@/views/pms/workflow/presentation'

type Status = AttemptStatus | 'WAITING'
type Node = WorkflowDefinition['nodes'][number]
const props = defineProps<{ definition: WorkflowDefinition, detail?: Detail }>()
const statusLabels: Record<Status, string> = {
  ACTIVE: '进行中',
  WAITING_ASSIGNMENT: '待分派',
  DONE: '已完成',
  CANCELLED: '已取消',
  WITHDRAWN: '已收回',
  RETURNED: '已驳回',
  WAITING: '未开始',
}
const statusTypes: Partial<Record<Status, 'success' | 'info' | 'warning'>> = {
  DONE: 'success',
  ACTIVE: 'info',
  WAITING_ASSIGNMENT: 'warning',
}

const stages = computed(() => {
  const { detail, definition } = props
  const attempts = new Map((detail?.nodes ?? []).map(attempt => [attempt.nodeKey, attempt]))
  const roots = definition.nodes.filter(node => !node.parentKey).sort((first, second) => first.order - second.order)
  return roots.map((root) => {
    const pastCurrent = detail && root.order > detail.instance.currentStepNo
    const view = (node: Node) => {
      const attempt = pastCurrent
        ? undefined
        : attempts.get(node.key)
      return {
        key: node.key,
        title: node.title,
        status: attempt?.status ?? 'WAITING' as const,
        processedNames: attempt?.processedNames ?? [],
        assigneeNames: attempt?.assigneeNames ?? [],
        plannedNames: detail?.plannedAssignees?.[node.key] ?? [],
        roleName: attempt?.roleName ?? handlingRoles(node).map(code => detail?.roleNames?.[code] ?? code).join('、'),
      }
    }
    const children = definition.nodes.filter(node => node.parentKey === root.key).sort((first, second) => first.order - second.order)
    return {
      key: root.key,
      current: detail?.instance.status === 'RUNNING' && root.order === detail.instance.currentStepNo,
      nodes: [
        view(root),
        ...children.map(view),
      ],
    }
  })
})
</script>

<style scoped>
.flow-scroll {
  overflow-x: auto;
  padding: 2px 2px 8px;
}
.flow-stages {
  display: flex;
  align-items: flex-start;
  width: max-content;
  max-width: none;
  margin: 0;
  padding: 0;
  list-style: none;
}
.flow-stage {
  display: flex;
  align-items: flex-start;
}
.flow-arrow {
  flex: none;
  margin: 21px 10px 0;
  color: var(--n-title-text-color);
  opacity: 0.65;
}
.flow-stage-body {
  box-sizing: border-box;
  width: 230px;
  padding: 6px;
  border: 1px solid var(--n-border-color);
  border-radius: 8px;
}
.flow-stage-body.is-current {
  border-color: var(--primary-color, #2080f0);
}
.flow-node {
  border: 1px solid var(--n-border-color);
  border-radius: 5px;
  padding: 7px 8px;
}
.node-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 6px;
  overflow-wrap: anywhere;
}
.node-heading :deep(.n-tag) {
  flex: none;
}
.node-people {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}
.branch-node {
  position: relative;
  padding: 6px 0 0 16px;
}
.branch-node::before {
  position: absolute;
  inset: 0 auto 0 7px;
  border-left: 1px solid currentColor;
  opacity: 0.35;
  content: '';
}
.branch-node:last-child::before {
  bottom: auto;
  height: 22px;
}
.branch-node::after {
  position: absolute;
  top: 21px;
  left: 7px;
  width: 9px;
  border-top: 1px solid currentColor;
  opacity: 0.35;
  content: '';
}
</style>
