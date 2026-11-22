<template>
  <n-drawer :show="true" width="min(680px, 96vw)" :mask-closable="false" :close-on-esc="!sending" @update:show="close">
    <n-drawer-content :title="t(sceneCode ? '编辑流程通知场景' : '新增流程通知场景')" :closable="!sending">
      <n-alert v-if="sceneError" type="error" class="mb-12">
        {{ errorMessage(sceneError, t('加载失败')) }}
        <n-button text @click="reloadScene">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-spin :show="loading">
        <n-form id="workflow-scene-form" :disabled="sending || loading" @submit.prevent="confirmSave">
          <template v-if="!sceneCode || scene">
            <template v-if="system">
              <n-descriptions :column="1" label-placement="left" size="small" bordered class="mb-12">
                <n-descriptions-item :label="t('场景名称')">
                  {{ displayName(form.nameCn, form.nameEn) }}
                </n-descriptions-item>
                <n-descriptions-item :label="t('场景编码')">
                  {{ form.sceneCode }}
                </n-descriptions-item>
              </n-descriptions>
              <n-alert class="mb-12" type="info">
                {{ t('系统办理提醒始终启用，收件人来自实际待办。') }}
              </n-alert>
            </template>
            <template v-else>
              <n-form-item :label="t('场景编码')" required>
                <n-input v-model:value="form.sceneCode" :disabled="!!sceneCode" :maxlength="64" :input-props="{ 'aria-label': t('场景编码') }" placeholder="workflow.review.approved" />
                <template v-if="!sceneCode" #feedback>
                  {{ t('编码以小写字母开头，可包含小写字母、数字、点和下划线。创建后不可修改。') }}
                </template>
              </n-form-item>
              <n-grid cols="1 520:2" :x-gap="16" responsive="screen">
                <n-form-item-gi :label="t('中文名称')" required>
                  <n-input v-model:value="form.nameCn" :maxlength="100" :input-props="{ 'aria-label': t('中文名称') }" />
                </n-form-item-gi>
                <n-form-item-gi :label="t('英文名称')">
                  <n-input v-model:value="form.nameEn" :maxlength="100" :input-props="{ 'aria-label': t('英文名称') }" />
                </n-form-item-gi>
              </n-grid>
              <n-form-item :label="t('站内标题')" required>
                <n-input v-model:value="form.titleTemplate" :input-props="{ 'aria-label': t('站内标题') }" />
              </n-form-item>
              <n-form-item :label="t('站内摘要')">
                <n-input v-model:value="form.summaryTemplate" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" :input-props="{ 'aria-label': t('站内摘要') }" />
                <template #feedback>
                  {{ t('标题限 200 个字符，摘要限 1,000 个字符。支持以下占位符，退回目标变量可为空。') }}
                  <div class="mt-4 flex flex-wrap gap-x-8">
                    <code v-for="placeholder in placeholders" :key="placeholder">{{ placeholder }}</code>
                  </div>
                </template>
              </n-form-item>
              <n-form-item :label="t('启用')">
                <n-switch v-model:value="form.enabled" :aria-label="t('启用')" />
              </n-form-item>
            </template>

            <n-alert v-if="mailError" class="mb-12" type="error">
              {{ t('邮件模板加载失败') }}
              <n-button text @click="reloadMail">
                {{ t('重试') }}
              </n-button>
            </n-alert>
            <n-form-item :label="t('补充邮件模板')">
              <n-select
                v-model:value="form.mailTemplateId"
                :options="mailOptions"
                :loading="mailLoading"
                :aria-label="t('补充邮件模板')"
                :placeholder="t('不发送补充邮件')"
                clearable
                filterable
              />
            </n-form-item>

            <template v-if="scene && !system">
              <n-descriptions :column="1" label-placement="left" size="small" bordered>
                <n-descriptions-item :label="t('已绑定群组（{0}）', scene.groups.length)">
                  {{ scene.groups.map(group => group.name).join('、') || '—' }}
                </n-descriptions-item>
                <n-descriptions-item :label="t('引用范围')">
                  <n-spin :show="usagesLoading">
                    <template v-if="usages">
                      {{ usageText(usages) }}
                    </template>
                    <template v-else-if="usagesError">
                      {{ t('加载失败') }}
                      <n-button text @click="reloadUsages">
                        {{ t('重试') }}
                      </n-button>
                    </template>
                  </n-spin>
                </n-descriptions-item>
              </n-descriptions>
            </template>
          </template>
        </n-form>
      </n-spin>

      <template #footer>
        <div class="w-full">
          <n-alert v-if="conflict" type="warning" class="mb-12">
            {{ t('配置已被他人更新。当前草稿已保留，请核对后重新加载已保存的配置。') }}
            <n-button text type="primary" :disabled="loading" @click="confirmReplaceDraft(reloadScene)">
              {{ t('重新加载已保存配置') }}
            </n-button>
          </n-alert>
          <n-alert v-else-if="saveError" type="error" class="mb-12">
            {{ saveError }}
          </n-alert>
          <n-space justify="end">
            <RouterLink v-if="sceneCode && canAudit" v-slot="{ href, navigate }" :to="auditLocation('NOTIFICATION_SCENE', sceneCode)" custom>
              <n-button tag="a" :href="href" :disabled="sending" @click="navigate">
                {{ t('查看操作记录') }}
              </n-button>
            </RouterLink>
            <n-button :disabled="sending" @click="emit('close')">
              {{ t('取消') }}
            </n-button>
            <n-button type="primary" attr-type="submit" form="workflow-scene-form" :loading="sending" :disabled="!canSave">
              {{ t('保存') }}
            </n-button>
          </n-space>
        </div>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import type { WorkflowSceneUsages, WorkflowSceneWrite } from './api'
import { computed, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import api from './api'
import { auditLocation, confirmReplaceDraft, countTrimmedCharacters, errorMessage, errorReason, SCENE_CODE, workflowSceneWrite } from './model'
import { useLatestResource } from './useLatestResource'

const props = defineProps<{ sceneCode?: string, canAudit: boolean }>()
const emit = defineEmits<{ close: [], saved: [] }>()
const form = reactive<WorkflowSceneWrite>({
  sceneCode: '',
  nameCn: '',
  nameEn: '',
  titleTemplate: '',
  summaryTemplate: '',
  enabled: true,
  mailTemplateId: null,
})
const placeholders = ['{{event.type}}', '{{node.name}}', '{{targetNode.name}}']
const sending = ref(false)
const saveError = ref('')
const conflict = ref(false)

const { data: scene, loading, error: sceneError, reload: reloadScene } = useLatestResource(() => {
  const { sceneCode } = props
  return sceneCode
    ? async signal => (await api.scene(sceneCode, signal)).data
    : null
})
const system = computed(() => scene.value?.sceneType === 'WORKFLOW_SYSTEM')
const { data: usages, loading: usagesLoading, error: usagesError, reload: reloadUsages } = useLatestResource(() => {
  const current = scene.value
  return current?.sceneType === 'WORKFLOW_EXTRA'
    ? async signal => (await api.workflowSceneUsages(current.sceneCode, signal)).data
    : null
})
const { data: templates, loading: mailLoading, error: mailError, reload: reloadMail } = useLatestResource(
  () => async signal => (await api.workflowMailTemplates(signal)).data,
)
const mailOptions = computed(() => {
  const options = (templates.value ?? []).map(template => ({
    label: template.enable
      ? `${template.name} · ${template.code}`
      : t('{0}（不可用）', `${template.name} · ${template.code}`),
    value: template.id,
    disabled: !template.enable,
  }))
  const currentId = form.mailTemplateId
  if (currentId != null && !options.some(option => option.value === currentId)) {
    options.push({
      label: t('邮件模板 #{0}', currentId),
      value: currentId,
      disabled: true,
    })
  }
  return options
})
const disabling = computed(() => scene.value?.sceneType === 'WORKFLOW_EXTRA' && scene.value.enabled && !form.enabled)
const canSave = computed(() => {
  if (sending.value || loading.value || conflict.value || (props.sceneCode && !scene.value) || (disabling.value && !usages.value))
    return false
  return system.value || (
    SCENE_CODE.test(form.sceneCode.trim())
    && !!form.nameCn.trim()
    && !!form.titleTemplate?.trim()
    && countTrimmedCharacters(form.titleTemplate) <= 200
    && countTrimmedCharacters(form.summaryTemplate ?? '') <= 1000
  )
})

watch(scene, (current) => {
  if (!current)
    return
  Object.assign(form, workflowSceneWrite(current))
  saveError.value = ''
  conflict.value = false
})

function usageText(value: WorkflowSceneUsages) {
  return t('{0} 个已发布模板版本，{1} 个运行中流程', value.publishedDefinitionCount, value.runningInstanceCount)
}

function close(show: boolean) {
  if (!show && !sending.value)
    emit('close')
}

function confirmSave() {
  if (!canSave.value)
    return
  if (!disabling.value || !usages.value) {
    void save()
    return
  }
  window.$dialog.warning({
    title: t('停用流程通知场景'),
    content: t('「{0}」影响 {1}。停用后将跳过尚未固定收件人的投递。', displayName(form.nameCn, form.nameEn), usageText(usages.value)),
    positiveText: t('停用'),
    positiveButtonProps: { type: 'error' },
    negativeText: t('取消'),
    onPositiveClick: save,
  })
}

async function save() {
  if (!canSave.value)
    return false
  sending.value = true
  saveError.value = ''
  try {
    const current = scene.value
    const data = workflowSceneWrite(form)
    if (current)
      await api.updateWorkflowScene(current.sceneCode, { ...data, expectedVersion: current.version })
    else
      await api.createWorkflowScene(data)
    emit('saved')
    return true
  }
  catch (failure) {
    if (errorReason(failure) === 'VERSION_CONFLICT')
      conflict.value = true
    else
      saveError.value = errorMessage(failure, t('保存失败'))
    return true
  }
  finally {
    sending.value = false
  }
}
</script>
