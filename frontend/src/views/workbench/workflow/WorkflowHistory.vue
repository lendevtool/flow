<template>
  <n-card size="small" :title="t('审批记录')">
    <n-empty v-if="!records.length" :description="t('暂无审批记录')" />
    <template v-else>
      <div class="history-grid">
        <div class="arrow" aria-hidden="true">
          ↑
        </div>
        <div v-for="(column, index) in columns" :key="index" class="history-group">
          <div v-if="index === 1" class="bridge" aria-hidden="true">
            ←
          </div>
          <div v-for="record in column" :key="record.id" class="history-entry">
            <div class="history-heading">
              <strong>{{ names[record.nodeKey ?? ''] || t('流程') }}</strong>
              <div class="history-result">
                <n-text depth="3" class="history-actor">
                  {{ record.actorName || '—' }}{{ record.identity ? ` · ${t(identities[record.identity])}` : '' }} · {{ record.createdAt.replace('T', ' ').slice(0, 16) }}
                </n-text>
                <n-tag size="small" :type="record.type === 'COMPLETED' ? 'success' : 'default'">
                  {{ t(eventLabels[record.type] || record.type) }}
                </n-tag>
              </div>
            </div>
            <p v-if="record.targetNodeKey">
              {{ t('目标节点') }} · {{ names[record.targetNodeKey] || record.targetNodeKey }}
            </p>
            <p v-if="record.reason">
              {{ record.reason }}
            </p>
            <div v-if="record.opinion" class="history-opinion" v-html="renderOpinion(record.opinion)" />
          </div>
        </div>
        <div v-if="columns.length > 1" class="arrow end-arrow" aria-hidden="true">
          ↑
        </div>
      </div>
      <n-space v-if="total > historyPageSize" class="mt-10" justify="end">
        <n-pagination :page="page" :page-size="historyPageSize" :item-count="total" @update:page="$emit('page', $event)" />
      </n-space>
    </template>
  </n-card>
</template>

<script setup lang="ts">
import type { History } from './api'
import { computed } from 'vue'
import { t } from '@/i18n'
import { historyPageSize } from './api'
import { renderOpinion } from './markdown'

const props = defineProps<{ records: History[], names: Record<string, string>, total: number, page: number }>()
defineEmits<{ page: [number] }>()
const columnSize = historyPageSize / 2
const columns = computed(() => props.records.length > columnSize
  ? [
      props.records.slice(0, columnSize),
      props.records.slice(columnSize),
    ]
  : [props.records])
const identities = {
  PRIMARY: '主办',
  DELEGATE: '转办人',
  BROADCAST: '广播成员',
  COUNTERSIGN: '会签成员',
}
const eventLabels: Record<string, string> = {
  STARTED: '已发起',
  ADVANCED: '提交到下一节点',
  APPROVED: '已通过',
  RETURNED: '已驳回',
  RECALLED: '已收回',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  ASSIGNED: '已分派',
}
</script>

<style scoped>
.history-grid {
  display: grid;
  grid-template-columns: 16px 1fr;
  gap: 8px;
  align-items: stretch;
}
.history-grid:has(.end-arrow) {
  grid-template-columns: 16px minmax(0, 1fr) minmax(0, 1fr) 16px;
  column-gap: 23px;
}
.history-group {
  display: grid;
  align-content: start;
  gap: 7px;
  position: relative;
}
.history-entry {
  padding: 8px 10px;
  border: 1px solid #8884;
  border-radius: 7px;
  overflow-wrap: anywhere;
}
.history-heading,
.history-result {
  display: flex;
  align-items: center;
  gap: 6px 10px;
}
.history-heading {
  flex-wrap: wrap;
}
.history-result {
  margin-inline-start: auto;
  min-width: 0;
}
.history-result :deep(.n-tag) {
  flex: none;
}
.history-actor {
  font-size: 12px;
  text-align: end;
}
.arrow,
.bridge {
  color: #18805b;
  text-align: center;
}
.arrow {
  border-left: 1px solid #18805b55;
}
.bridge {
  position: absolute;
  left: -20px;
  top: 45%;
  font-size: 19px;
}
.history-opinion :deep(ul),
.history-opinion :deep(ol) {
  padding-left: 20px;
  list-style: revert;
}
.history-opinion :deep(h1),
.history-opinion :deep(h2),
.history-opinion :deep(h3),
.history-opinion :deep(h4),
.history-opinion :deep(h5) {
  font-weight: 650;
}
@media (max-width: 600px) {
  .history-grid:has(.end-arrow) {
    grid-template-columns: 16px minmax(0, 1fr);
    column-gap: 8px;
  }
  .history-group {
    grid-column: 2;
  }
  .bridge,
  .end-arrow {
    display: none;
  }
}
</style>
