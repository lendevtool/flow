<template>
  <n-modal preset="card" :show="true" :title="t('流程主备')" style="width: min(520px, calc(100vw - 32px))" @update:show="!$event && !saving && emit('close')">
    <n-spin :show="loading">
      <n-form @submit.prevent="save">
        <n-form-item :label="t('主办')">
          <n-select v-model:value="primary" filterable clearable :options="members" @update:value="removePrimaryBackup" />
        </n-form-item>
        <n-form-item :label="t('备办')">
          <n-select v-model:value="backups" multiple filterable clearable :options="members.filter(member => member.value !== primary)" />
        </n-form-item>
        <n-space justify="end">
          <n-button :disabled="saving" @click="emit('close')">
            {{ t('取消') }}
          </n-button>
          <n-button type="primary" attr-type="submit" :loading="saving" :disabled="loading || !ready">
            {{ t('保存') }}
          </n-button>
        </n-space>
      </n-form>
    </n-spin>
  </n-modal>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { t } from '@/i18n'
import api from '@/views/workbench/workflow/api'

const props = defineProps<{ roleId: number }>()
const emit = defineEmits<{ close: [], saved: [] }>()
const primary = ref<number | null>(null)
const backups = ref<number[]>([])
const members = ref<{ value: number, label: string, disabled: boolean }[]>([])
const loading = ref(true)
const ready = ref(false)
const saving = ref(false)

function removePrimaryBackup() {
  backups.value = backups.value.filter(id => id !== primary.value)
}
async function save() {
  if (saving.value || !ready.value)
    return
  saving.value = true
  try {
    await api.saveHandlers(props.roleId, { primaryUserId: primary.value, backupUserIds: backups.value })
    window.$message.success(t('操作成功'))
    emit('saved')
    emit('close')
  }
  catch {
    // 请求错误由统一拦截器显示。
  }
  finally {
    saving.value = false
  }
}
onMounted(async () => {
  try {
    const { data } = await api.handlers(props.roleId)
    primary.value = data.primaryUserId
    backups.value = data.backupUserIds
    members.value = data.members.map(person => ({ value: person.id, label: person.name, disabled: !person.enabled }))
    ready.value = true
  }
  catch {
    // 请求错误由统一拦截器显示。
  }
  finally {
    loading.value = false
  }
})
</script>
