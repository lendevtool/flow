<template>
  <OrderEditor
    v-model:show="show"
    v-model:rows="rows"
    :title="t('调整排序 · {0}', displayName(group.name, group.nameEn))"
    :description="t('拖动{0}或使用箭头调整顺序，保存后按当前顺序重新编号。', groupMode ? t('分类') : t('明细'))"
    :saving="saving"
    @save="save"
  />
</template>

<script setup>
import OrderEditor from '@/components/common/OrderEditor.vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import api from '../api'
import { toPlain } from '../fields'

const emit = defineEmits(['saved', 'groupsSaved'])
const show = ref(false)
const saving = ref(false)
const group = ref({})
const groupMode = ref(false)
const rows = ref([])
function open(category, data) {
  groupMode.value = false
  group.value = toPlain(category)
  rows.value = data.map(row => ({
    id: row.id,
    label: displayName(row.name, row.nameEn),
    detail: row.code,
  }))
  show.value = true
}
function openGroups(data) {
  open({
    name: t('参数分类'),
  }, data)
  groupMode.value = true
}
async function save() {
  if (saving.value)
    return
  saving.value = true
  try {
    const ids = rows.value.map(row => row.id)
    if (groupMode.value)
      await api.sortGroups(ids)
    else
      await api.sort(group.value.id, ids)
    show.value = false
    $message.success(t('排序已保存'))
    if (groupMode.value)
      emit('groupsSaved', ids)
    else
      emit('saved', group.value.id)
  }
  catch { /* Request client displays write errors. */ }
  finally { saving.value = false }
}
defineExpose({ open, openGroups })
</script>
