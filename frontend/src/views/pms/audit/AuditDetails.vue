<template>
  <n-drawer v-model:show="show" width="min(1100px, 96vw)" @update:show="closed">
    <n-drawer-content :title="t('操作详情')" closable>
      <n-alert v-if="error" type="error" class="mb-16">
        {{ error }}
        <n-button text @click="load">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-spin :show="loading">
        <template v-if="log">
          <div class="mb-20 flex flex-wrap items-center gap-12">
            <n-tag :type="resultInfo(log.result).type" :bordered="false">
              {{ resultInfo(log.result).label }}
            </n-tag>
            <span class="text-17 font-medium">{{ operationSummary(log) }}</span>
          </div>
          <n-descriptions bordered :column="2" label-placement="left" size="small">
            <n-descriptions-item :label="t('操作者')">
              <PersonLink :user-id="log.actorType === 'USER' ? log.actorId : null" :label="actorLabel(log)" />
            </n-descriptions-item>
            <n-descriptions-item :label="t('当时角色')">
              {{ log.actorRoleName || log.actorRoleCode || '—' }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('操作时间')">
              {{ timeText(log.occurredAt) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('耗时')">
              {{ log.durationMs === null ? t('未知') : `${log.durationMs} ms` }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('来源 IP')">
              {{ log.sourceIp || '—' }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('请求路径')">
              {{ log.requestMethod || '' }} {{ log.requestPath || t('内部调用') }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('关联请求')" :span="2">
              <span class="break-all text-12">{{ log.requestId || '—' }}</span>
            </n-descriptions-item>
            <n-descriptions-item :label="t('同次操作')" :span="2">
              <n-button text type="primary" @click="emit('operation', log.operationId)">
                {{ log.operationId }}
              </n-button>
            </n-descriptions-item>
          </n-descriptions>
          <n-alert v-if="log.errorMessage" :type="log.result === 'UNKNOWN' ? 'warning' : 'error'" class="mt-16">
            {{ t(log.errorMessage) }}
            <span v-if="log.errorCode" class="ml-8 text-12 opacity-60">{{ log.errorCode }}</span>
          </n-alert>
          <div class="my-20 text-14 opacity-70">
            {{ t('涉及 {0} 个对象，{1} 个对象有变化，共 {2} 项字段变化。', log.objectCount, log.changedObjectCount, log.fieldChangeCount) }}
          </div>
          <n-empty v-if="!objects.length" :description="t('此操作没有数据变更明细')" class="my-32" />
          <n-collapse v-else :default-expanded-names="objects.length === 1 ? [objects[0].objectNo] : []">
            <n-collapse-item v-for="object in objects" :key="object.objectNo" :name="object.objectNo">
              <template #header>
                <div class="flex flex-wrap items-center gap-8">
                  <PersonLink :user-id="object.objectType === 'USER' ? object.objectId : null" :label="object.objectName" />
                  <n-tag v-if="object.objectCode" size="small" :bordered="false">
                    {{ object.objectCode }}
                  </n-tag>
                  <span class="text-12 opacity-50">{{ object.entryType === 'REFERENCE' ? t('关联对象') : t('{0} 项变化', object.fieldChangeCount) }}</span>
                </div>
              </template>
              <template #header-extra>
                <n-button text type="primary" @click.stop="emit('history', object)">
                  {{ t('对象历史') }}
                </n-button>
              </template>
              <div class="mb-12 break-all text-12 opacity-50">
                {{ objectTypeLabel(object.objectType) }} · ID {{ object.objectId }}
                <span v-if="object.parentId"> {{ t('· 所属 {0} / {1}', object.parentType, object.parentId) }}</span>
              </div>
              <n-data-table v-if="object.changes?.length" :columns="columns" :data="object.changes" :pagination="false" :size="appStore.tableSize" :single-line="false" :scroll-x="580" />
              <n-alert v-else type="info" :show-icon="false">
                {{ object.entryType === 'REFERENCE' ? t('记录此对象与操作的关联，不表示对象数据发生变化。') : t('没有业务字段变化。') }}
              </n-alert>
              <n-collapse v-if="object.entryType !== 'REFERENCE'" class="mt-16">
                <n-collapse-item :title="t('查看当时记录的数据')" name="snapshots">
                  <div class="snapshot-grid">
                    <n-card :title="t('操作前')" size="small">
                      <JsonViewer :value="object.beforeData" />
                    </n-card>
                    <n-card :title="t('操作后')" size="small">
                      <JsonViewer :value="object.afterData" />
                    </n-card>
                  </div>
                </n-collapse-item>
              </n-collapse>
            </n-collapse-item>
          </n-collapse>
          <div v-if="total > 10" class="mt-20 flex justify-end">
            <n-pagination v-model:page="page" :page-size="10" :item-count="total" :disabled="loading" @update:page="load" />
          </div>
        </template>
      </n-spin>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup>
import { NText } from 'naive-ui'
import { onBeforeUnmount } from 'vue'
import PersonLink from '@/components/common/PersonLink.vue'
import { t } from '@/i18n'
import { useAppStore } from '@/store'
import api from './api'
import { actorLabel, cellText, objectTypeLabel, operationSummary, resultInfo, timeText } from './format'

const emit = defineEmits(['history', 'operation'])
const appStore = useAppStore()
const show = ref(false)
const loading = ref(false)
const log = ref(null)
const objects = ref([])
const total = ref(0)
const page = ref(1)
const error = ref('')
let selectedId
let generation = 0
function valueCell(cell) {
  return h('div', {
    class: 'audit-cell',
    style: {
      opacity: cell?.redacted ? 0.6 : 1,
    },
  }, cellText(cell))
}
const columns = computed(() => [
  {
    title: t('字段'),
    key: 'label',
    width: 180,
    render: row => h('div', [
      h('div', t(row.label)),
      h(NText, {
        depth: 3,
        style: { fontSize: '12px' },
      }, () => row.path),
    ]),
  },
  {
    title: t('操作前'),
    key: 'before',
    minWidth: 200,
    render: row => valueCell(row.before),
  },
  {
    title: t('操作后'),
    key: 'after',
    minWidth: 200,
    render: row => valueCell(row.after),
  },
])

async function load() {
  const own = ++generation
  loading.value = true
  error.value = ''
  try {
    const { data } = await api.detail(selectedId, { pageNo: page.value, pageSize: 10 })
    if (own !== generation)
      return
    log.value = data.log
    objects.value = data.objects.pageData
    total.value = data.objects.total
  }
  catch (failure) {
    if (own === generation) {
      log.value = null
      objects.value = []
      error.value = failure?.message || t('加载详情失败')
    }
  }
  finally {
    if (own === generation)
      loading.value = false
  }
}

function open(id) {
  selectedId = id
  log.value = null
  objects.value = []
  page.value = 1
  show.value = true
  load()
}
function closed(value) {
  if (!value) {
    generation++
    log.value = null
    objects.value = []
  }
}
function close() {
  show.value = false
  closed(false)
}
onBeforeUnmount(() => generation++)
defineExpose({ open, close })
</script>

<style scoped>
.audit-cell {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.snapshot-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
@media (max-width: 700px) {
  .snapshot-grid {
    grid-template-columns: 1fr;
  }
}
</style>
