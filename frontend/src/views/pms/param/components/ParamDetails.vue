<template>
  <n-drawer v-model:show="show" width="min(560px, 94vw)">
    <n-drawer-content :title="isGroup ? t('参数分类详情') : t('参数明细详情')" closable>
      <n-descriptions bordered :column="1" label-placement="left" :label-style="{ width: '120px' }">
        <n-descriptions-item :label="t('编码')">
          {{ record.code }}
        </n-descriptions-item>
        <n-descriptions-item :label="t('名称')">
          {{ displayName(record.name, record.nameEn) }}
        </n-descriptions-item>
        <n-descriptions-item :label="t('状态')">
          <n-tag size="small" :type="record.enable ? 'success' : 'default'">
            {{ record.enable ? t('启用') : t('停用') }}
          </n-tag>
        </n-descriptions-item>
        <n-descriptions-item :label="t('排序')">
          {{ record.sort }}
        </n-descriptions-item>
        <template v-if="!isGroup">
          <n-descriptions-item v-for="field in fields" :key="`${field.storage}-${field.key}`" :label="field.label">
            {{ displayValue(field, field.storage === 'fixed' ? record[field.key] : record.extra?.[field.key]) }}
          </n-descriptions-item>
        </template>
        <n-descriptions-item :label="t('备注')">
          {{ record.remark || '—' }}
        </n-descriptions-item>
        <n-descriptions-item :label="t('创建人')">
          {{ record.createdByName || '—' }}
        </n-descriptions-item>
        <n-descriptions-item :label="t('创建时间')">
          {{ time(record.createdAt) }}
        </n-descriptions-item>
        <n-descriptions-item :label="t('更新人')">
          {{ record.updatedByName || '—' }}
        </n-descriptions-item>
        <n-descriptions-item :label="t('更新时间')">
          {{ time(record.updatedAt) }}
        </n-descriptions-item>
      </n-descriptions>
      <template v-if="isGroup && record.fieldConfig">
        <n-divider title-placement="left">
          {{ t('字段配置') }}
        </n-divider>
        <JsonViewer :value="record.fieldConfig" />
      </template>
      <template v-if="!isGroup && record.extra && Object.keys(record.extra).length">
        <n-divider title-placement="left">
          {{ t('完整扩展属性') }}
        </n-divider>
        <JsonViewer :value="record.extra" />
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup>
import dayjs from 'dayjs'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import { displayValue, fieldDefinitions, toPlain } from '../fields'

const show = ref(false)
const record = ref({})
const isGroup = ref(false)
const fields = ref([])
function time(value) {
  return value
    ? dayjs(value).format('YYYY-MM-DD HH:mm:ss')
    : '—'
}
function open(row, group) {
  record.value = toPlain(row)
  isGroup.value = !group
  fields.value = group
    ? fieldDefinitions(group.fieldConfig).filter(field => field.storage === 'extra' || (row[field.key] !== null && row[field.key] !== undefined))
    : []
  show.value = true
}
defineExpose({ open })
</script>
