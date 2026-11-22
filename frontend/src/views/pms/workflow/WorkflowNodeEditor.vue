<template>
  <n-drawer :show="true" show-mask="transparent" width="min(680px, 100vw)" :aria-label="title" @update:show="!$event && emit('close')">
    <n-drawer-content :title="title" closable>
      <n-alert v-if="parentTitle" class="mb-12" :type="convertingParent ? 'warning' : 'info'">
        {{ t('所属一级节点') }} {{ parentTitle }}
        <p v-if="convertingParent">
          {{ t('保存首个子节点后，父节点变为自动汇总，原处理角色、字段配置和驳回后通知场景会移除。') }}
        </p>
      </n-alert>
      <n-form @submit.prevent="save">
        <n-form-item :label="t('节点名称')" required>
          <n-input v-model:value="form.title" :maxlength="200" />
        </n-form-item>
        <n-alert v-if="summary" type="info">
          {{ t('全部子节点完成后自动推进。') }}
        </n-alert>
        <template v-else>
          <n-grid cols="1 520:2" responsive="screen" :x-gap="16">
            <n-form-item-gi :label="t('处理方式')" required>
              <n-select :value="form.mode" :options="modes" @update:value="setMode" />
            </n-form-item-gi>
            <n-form-item-gi v-if="multiRole" :label="t('处理角色')" required>
              <n-select
                v-model:value="form.roleCodes"
                :options="multiRoleOptions"
                :loading="rolesLoading"
                :disabled="rolesLoading"
                :placeholder="t('请选择至少两个角色')"
                :max-tag-count="3"
                multiple
                filterable
              />
            </n-form-item-gi>
            <n-form-item-gi v-else :label="t('处理角色')" required>
              <n-select v-model:value="form.roleCode" :options="roles" filterable />
            </n-form-item-gi>
          </n-grid>
          <n-alert v-if="multiRole && !rolesLoading && unavailableRoles.length" class="mb-12" type="error">
            {{ t('以下角色没有可处理人员：{0}', unavailableRoles.join('、')) }}
          </n-alert>
          <n-form-item :label="t('截止天数')">
            <n-input-number v-model:value="form.dueOffsetDays" :min="0" :max="3650" :precision="0" clearable />
          </n-form-item>
          <n-table v-if="fields.length" size="small" :bordered="false" class="mb-12">
            <thead><tr><th>{{ t('字段') }}</th><th>{{ t('可编辑') }}</th><th>{{ t('提交时必填') }}</th></tr></thead>
            <tbody>
              <tr v-for="field in fields" :key="field">
                <td>{{ labels.get(field) || field }}</td>
                <td>
                  <n-checkbox :checked="editable.includes(field)" :disabled="isCountersign(form.mode)" :aria-label="`${labels.get(field) || field} ${t('可编辑')}`" @update:checked="toggleEditable(field, $event)" />
                </td>
                <td>
                  <n-checkbox :checked="form.requiredFields.includes(field)" :disabled="!editable.includes(field)" :aria-label="`${labels.get(field) || field} ${t('提交时必填')}`" @update:checked="toggleRequired(field, $event)" />
                </td>
              </tr>
            </tbody>
          </n-table>
        </template>
        <n-divider title-placement="left">
          {{ t('流程通知') }}
        </n-divider>
        <n-alert v-if="!summary" class="mb-12" type="info">
          {{ t('系统会在生成新待办或退回待办时向实际办理人发送站内提醒。') }}
        </n-alert>
        <n-alert v-if="scenesFailed" class="mb-12" type="error">
          {{ t('通知场景加载失败') }}
          <n-button text @click="emit('reloadScenes')">
            {{ t('重试') }}
          </n-button>
        </n-alert>
        <n-grid cols="1 520:2" responsive="screen" :x-gap="16">
          <n-form-item-gi :label="t('通过后通知场景')">
            <n-select
              :value="form.onApprovedSceneCode ?? null"
              :options="sceneOptions(form.onApprovedSceneCode)"
              :loading="scenesLoading"
              :aria-label="t('通过后通知场景')"
              :placeholder="t('选择通知场景（可选）')"
              clearable
              filterable
              @update:value="form.onApprovedSceneCode = $event"
            />
          </n-form-item-gi>
          <n-form-item-gi v-if="!summary" :label="t('驳回后通知场景')">
            <n-select
              :value="form.onRejectedSceneCode ?? null"
              :options="sceneOptions(form.onRejectedSceneCode)"
              :loading="scenesLoading"
              :aria-label="t('驳回后通知场景')"
              :placeholder="t('选择通知场景（可选）')"
              clearable
              filterable
              @update:value="form.onRejectedSceneCode = $event"
            />
          </n-form-item-gi>
        </n-grid>
        <n-space justify="end">
          <n-button @click="emit('close')">
            {{ t('取消') }}
          </n-button>
          <n-button type="primary" attr-type="submit" :disabled="!canSave">
            {{ t('保存节点') }}
          </n-button>
        </n-space>
      </n-form>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { HandlingMode, WorkflowNode, WorkflowNotificationScene } from './api'
import { computed, reactive } from 'vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import { handlingModeLabels, isCountersign } from './presentation'

const props = defineProps<{
  title: string
  node: WorkflowNode
  fields: string[]
  labels: Map<string, string>
  roles: { value: string, label: string, hasEnabledMembers: boolean }[]
  rolesLoading: boolean
  scenes: WorkflowNotificationScene[]
  scenesLoading: boolean
  scenesFailed: boolean
  summary: boolean
  parentTitle?: string
  convertingParent?: boolean
}>()
const emit = defineEmits<{ close: [], reloadScenes: [], save: [WorkflowNode] }>()
const form = reactive({
  ...props.node,
  roleCodes: [
    ...(props.node.roleCodes ?? []),
  ],
  requiredFields: [...props.node.requiredFields],
})
const multiRole = computed(() => form.mode === 'MULTI_ALL')
const multiRoleOptions = computed(() => props.roles.map(role => ({
  value: role.value,
  label: role.hasEnabledMembers
    ? role.label
    : t('{0}（无可处理人员）', role.label),
  disabled: !role.hasEnabledMembers && !form.roleCodes.includes(role.value),
})))
const unavailableRoles = computed(() => {
  const byCode = new Map(props.roles.map(role => [role.value, role]))
  return form.roleCodes.flatMap((code) => {
    const role = byCode.get(code)
    return role?.hasEnabledMembers
      ? []
      : [role?.label ?? code]
  })
})
const canSave = computed(() => {
  if (!form.title.trim())
    return false
  if (props.summary)
    return true
  return multiRole.value
    ? !props.rolesLoading && form.roleCodes.length >= 2 && form.roleCodes.length <= 100 && unavailableRoles.value.length === 0
    : !!form.roleCode
})
const editable = computed(() => {
  if (isCountersign(form.mode))
    return []
  return form.editableFields ?? (form.parentKey == null && form.order === 1
    ? props.fields
    : [])
})
const modes = computed(() => Object.entries(handlingModeLabels).map(([value, label]) => ({
  value,
  label: t(label),
})))

function sceneOptions(current?: string | null) {
  const options = props.scenes.map(scene => ({
    value: scene.sceneCode,
    label: displayName(scene.nameCn, scene.nameEn),
    disabled: false,
  }))
  if (current && !options.some(option => option.value === current)) {
    options.push({
      value: current,
      label: props.scenesLoading || props.scenesFailed
        ? current
        : t('{0}（不可用）', current),
      disabled: true,
    })
  }
  return options
}

function setMode(mode: HandlingMode) {
  if (mode === 'MULTI_ALL' && form.roleCode && !form.roleCodes.length)
    form.roleCodes = [form.roleCode]
  form.mode = mode
  if (isCountersign(mode)) {
    form.editableFields = []
    form.requiredFields = []
  }
}

function toggleEditable(field: string, checked: boolean) {
  form.editableFields = checked
    ? [...editable.value, field]
    : editable.value.filter(code => code !== field)
  if (!checked)
    toggleRequired(field, false)
}

function toggleRequired(field: string, checked: boolean) {
  form.requiredFields = checked
    ? [...form.requiredFields, field]
    : form.requiredFields.filter(code => code !== field)
}

function save() {
  if (canSave.value) {
    emit('save', {
      ...form,
      title: form.title.trim(),
      roleCode: multiRole.value ? null : form.roleCode,
      roleCodes: multiRole.value
        ? [...form.roleCodes]
        : null,
    })
  }
}
</script>
