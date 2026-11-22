<template>
  <n-modal :show="true" preset="card" :title="t(mode === 'start' ? '发起流程' : '流程模板')" class="max-w-95vw w-760" :closable="!saving" :mask-closable="false" @close="emit('close')" @esc="!saving && emit('close')">
    <n-alert v-if="definitions.error.value" type="error" class="mb-12">
      {{ definitions.error.value }}
      <n-button text @click="loadDefinitions">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <n-alert v-if="assignees.error.value" type="error" class="mb-12">
      {{ assignees.error.value }}
      <n-button text @click="load">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <n-spin :show="definitions.loading.value">
      <n-form v-if="mode === 'start'" :disabled="saving" @submit.prevent="start">
        <n-form-item :label="t('已发布模板')" required>
          <n-select v-model:value="definitionId" :options="definitionOptions" @update:value="pickDefinition" />
        </n-form-item>
        <n-form-item :label="t('标题')" required>
          <n-input v-model:value="title" :maxlength="200" />
        </n-form-item>
        <n-form-item v-for="step in definition?.steps" :key="step.stepNo" :label="`${step.stepNo}. ${step.title}`" required>
          <n-select
            v-model:value="stepAssignees[step.stepNo]"
            :options="canAssign() ? stepOptions(step) : options()"
            :disabled="!canAssign()"
            :loading="assignees.loading.value"
            filterable
            :placeholder="t('选择负责人')"
          />
        </n-form-item>
        <n-collapse v-if="publicDimensions.length" class="mb-16">
          <n-collapse-item :title="t('公共维度')" name="public">
            <PublicDimensionFields v-model="dimensions" :dimensions="publicDimensions" />
          </n-collapse-item>
        </n-collapse>
        <n-alert v-if="error" type="error" class="mb-12">
          {{ error }}
        </n-alert>
        <n-space justify="end">
          <n-button :disabled="saving" @click="emit('close')">
            {{ t('取消') }}
          </n-button>
          <n-button type="primary" attr-type="submit" :loading="saving" :disabled="!definition">
            {{ t('发起流程') }}
          </n-button>
        </n-space>
      </n-form>

      <n-form v-else-if="draft" :disabled="saving" @submit.prevent="saveTemplate">
        <n-form-item :label="t('模板编码')" required>
          <n-input v-model:value="draft.code" :disabled="!!editing" :maxlength="64" />
        </n-form-item>
        <n-form-item :label="t('模板名称')" required>
          <n-input v-model:value="draft.name" :maxlength="200" />
        </n-form-item>
        <n-card v-for="(step, index) in draft.steps" :key="index" size="small" :title="t('步骤 {0}', index + 1)" class="mb-12">
          <template #header-extra>
            <n-space>
              <n-button :disabled="index === 0" text :aria-label="t('上移')" @click="moveStep(index, -1)">
                ↑
              </n-button>
              <n-button :disabled="index === draft.steps.length - 1" text :aria-label="t('下移')" @click="moveStep(index, 1)">
                ↓
              </n-button>
              <n-button :disabled="draft.steps.length === 1" text type="error" @click="draft.steps.splice(index, 1)">
                {{ t('删除') }}
              </n-button>
            </n-space>
          </template>
          <n-form-item :label="t('步骤名称')" required>
            <n-input v-model:value="step.title" :maxlength="200" />
          </n-form-item>
          <div class="grid grid-cols-2 gap-x-12">
            <n-form-item :label="t('默认负责人')">
              <n-select v-model:value="step.assigneeUserId" :options="stepOptions(step)" filterable clearable />
            </n-form-item>
            <n-form-item :label="t('截止偏移天数')">
              <n-input-number v-model:value="step.dueOffsetDays" :precision="0" :min="0" class="w-full" />
            </n-form-item>
          </div>
        </n-card>
        <n-button class="mb-12" @click="appendStep">
          {{ t('添加步骤') }}
        </n-button>
        <n-alert v-if="error" type="error" class="mb-12">
          {{ error }}
        </n-alert>
        <n-space justify="end">
          <n-button :disabled="saving" @click="closeEditor">
            {{ t('返回模板列表') }}
          </n-button>
          <n-button type="primary" attr-type="submit" :loading="saving">
            {{ t('保存草稿') }}
          </n-button>
        </n-space>
      </n-form>

      <template v-else>
        <n-space justify="end" class="mb-12">
          <n-button type="primary" :disabled="saving" @click="beginTemplate()">
            {{ t('新建模板') }}
          </n-button>
        </n-space>
        <n-alert v-if="error" type="error" class="mb-12">
          {{ error }}
        </n-alert>
        <n-list v-if="definitions.data.value.length">
          <n-list-item v-for="item in definitions.data.value" :key="item.id">
            <strong>{{ item.name }}</strong>
            <p>{{ item.code }} · v{{ item.revision }} · {{ t(templateLabels[item.status]) }}</p>
            <template #suffix>
              <n-space>
                <template v-if="item.status === 'DRAFT'">
                  <n-button size="small" :disabled="saving" @click="beginTemplate(item)">
                    {{ t('编辑') }}
                  </n-button>
                  <n-popconfirm @positive-click="changeStatus(item, 'publish')">
                    <template #trigger>
                      <n-button size="small" type="primary" :disabled="saving">
                        {{ t('发布') }}
                      </n-button>
                    </template>
                    {{ t('发布模板「{0}」？', item.name) }}
                  </n-popconfirm>
                </template>
                <template v-else>
                  <n-button size="small" :disabled="saving" @click="beginTemplate(item, true)">
                    {{ t('创建新版本') }}
                  </n-button>
                  <n-popconfirm v-if="item.status === 'PUBLISHED'" @positive-click="changeStatus(item, 'retire')">
                    <template #trigger>
                      <n-button size="small" type="warning" :disabled="saving">
                        {{ t('停用') }}
                      </n-button>
                    </template>
                    {{ t('停用模板「{0}」？', item.name) }}
                  </n-popconfirm>
                </template>
              </n-space>
            </template>
          </n-list-item>
        </n-list>
        <n-empty v-else :description="t('没有流程模板')" />
      </template>
    </n-spin>
  </n-modal>
</template>

<script setup lang="ts">
import type { FlowDefinition, FlowDefinitionWrite, FlowStep } from './advanced-api'
import type { PublicDimension } from './api'
import { computed, onMounted, ref } from 'vue'
import { t } from '@/i18n'
import { useUserStore } from '@/store'
import advanced from './advanced-api'
import { newWorkbenchKey, workbenchError } from './model'
import PublicDimensionFields from './PublicDimensionFields.vue'
import { useAssignees, useWorkbenchRequest } from './useWorkbench'

const props = defineProps<{ mode: 'start' | 'templates', publicDimensions: PublicDimension[] }>()
const emit = defineEmits<{ close: [], started: [todoId?: number | null] }>()
const definitions = useWorkbenchRequest<FlowDefinition[]>([])
const user = useUserStore()
const { assignees, canAssign, load, options } = useAssignees(() => true)
const definitionId = ref<number | null>(null)
const definition = computed(() => definitions.data.value.find(item => item.id === definitionId.value))
const title = ref('')
const stepAssignees = ref<Record<number, number | null>>({})
const dimensions = ref<Record<number, number>>({})
const draft = ref<FlowDefinitionWrite | null>(null)
const editing = ref<FlowDefinition | null>(null)
const saving = ref(false)
const error = ref('')
const requestKey = newWorkbenchKey()
const templateLabels = { DRAFT: '草稿', PUBLISHED: '已发布', RETIRED: '已停用' }
const definitionOptions = computed(() => definitions.data.value.map(item => ({ value: item.id, label: `${item.name} · v${item.revision}` })))
const loadDefinitions = () => definitions.run(() => advanced.definitions(props.mode === 'start'))

onMounted(() => {
  loadDefinitions()
  load()
})

function stepOptions(step: FlowStep) {
  const current = step.assigneeUserId == null
    ? undefined
    : {
        id: step.assigneeUserId,
        name: t('默认负责人 · {0}', step.assigneeUserId),
      }
  return options(current)
}

function pickDefinition(id: number | null) {
  const selected = definitions.data.value.find(item => item.id === id)
  if (selected && !title.value.trim())
    title.value = selected.name
  stepAssignees.value = Object.fromEntries((selected?.steps ?? []).map(step => [
    step.stepNo,
    canAssign()
      ? step.assigneeUserId ?? null
      : user.userId ?? null,
  ]))
}

async function start() {
  const selected = definition.value
  if (saving.value || !selected)
    return
  const assignments: Record<number, number> = {}
  error.value = ''
  if (!title.value.trim()) {
    error.value = t('请输入标题。')
    return
  }
  for (const step of selected.steps) {
    const assignee = stepAssignees.value[step.stepNo]
    if (assignee == null) {
      error.value = t('请为每一步选择负责人。')
      return
    }
    assignments[step.stepNo] = assignee
  }
  saving.value = true
  try {
    const { data } = await advanced.startFlow({
      requestKey,
      definitionId: selected.id,
      title: title.value.trim(),
      assignees: assignments,
      dimensions: dimensions.value,
    })
    emit('started', data.steps.find(step => step.stepNo === data.currentStepNo)?.todoId)
  }
  catch (cause) {
    error.value = workbenchError(cause, t('发起失败，请重试'))
  }
  finally {
    saving.value = false
  }
}

function beginTemplate(item?: FlowDefinition, copy = false) {
  error.value = ''
  editing.value = copy
    ? null
    : item ?? null
  draft.value = {
    code: item?.code ?? '',
    name: item?.name ?? '',
    steps: item?.steps.map(step => ({ ...step })) ?? [],
  }
  if (!draft.value.steps.length)
    appendStep()
}

function appendStep() {
  draft.value?.steps.push({
    stepNo: draft.value.steps.length + 1,
    title: '',
    assigneeUserId: null,
    dueOffsetDays: null,
  })
}

function moveStep(index: number, direction: number) {
  const steps = draft.value?.steps
  const target = index + direction
  if (steps && target >= 0 && target < steps.length) {
    const [step] = steps.splice(index, 1)
    steps.splice(target, 0, step)
  }
}

function closeEditor() {
  draft.value = null
  editing.value = null
  error.value = ''
}

async function saveTemplate() {
  const form = draft.value
  if (saving.value || !form)
    return
  const code = form.code.trim()
  const name = form.name.trim()
  const steps = form.steps.map((step, index) => ({
    ...step,
    stepNo: index + 1,
    title: step.title.trim(),
  }))
  if (!code || !name || !steps.length || steps.some(step => !step.title)) {
    error.value = t('请输入模板编码、模板名称及各步骤名称。')
    return
  }
  saving.value = true
  error.value = ''
  try {
    if (editing.value)
      await advanced.editDefinition(editing.value.id, { name, steps, version: editing.value.version })
    else
      await advanced.createDefinition({ code, name, steps })
    closeEditor()
    await loadDefinitions()
  }
  catch (cause) {
    error.value = workbenchError(cause, t('保存失败，请重试'))
  }
  finally {
    saving.value = false
  }
}

async function changeStatus(item: FlowDefinition, action: 'publish' | 'retire') {
  if (saving.value)
    return
  saving.value = true
  error.value = ''
  try {
    await advanced.definitionAction(item.id, action, item.version)
    await loadDefinitions()
  }
  catch (cause) {
    error.value = workbenchError(cause, t('操作失败，请重试'))
    await loadDefinitions()
  }
  finally {
    saving.value = false
  }
}
</script>
