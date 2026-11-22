<template>
  <n-card class="mt-20" :title="t('休假转办')">
    <n-form label-placement="left" :label-width="100" @submit.prevent="save">
      <n-form-item :label="t('转办人')" required>
        <n-select
          v-model:value="delegateUserId"
          :options="options"
          :loading="searching"
          filterable remote clearable
          @search="search"
        />
      </n-form-item>
      <n-form-item :label="t('休假开始')" required>
        <input v-model="startsAt" type="datetime-local" :aria-label="t('休假开始')" class="delegation-date">
      </n-form-item>
      <n-form-item :label="t('休假结束')" required>
        <input v-model="endsAt" type="datetime-local" :aria-label="t('休假结束')" class="delegation-date">
      </n-form-item>
      <p class="mb-12">
        {{ t('休假期间，新待办同时发给主办和转办人。时间使用北京时间。') }}
      </p>
      <n-button type="primary" attr-type="submit" :disabled="!delegateUserId || !startsAt || endsAt <= startsAt" :loading="saving">
        {{ t('保存并查看可补发待办') }}
      </n-button>
    </n-form>

    <n-divider>{{ t('流程转办记录') }}</n-divider>
    <n-table size="small" :bordered="false">
      <thead><tr><th>{{ t('转办人') }}</th><th>{{ t('休假开始') }}</th><th>{{ t('休假结束') }}</th><th>{{ t('状态') }}</th><th>{{ t('操作') }}</th></tr></thead>
      <tbody>
        <tr v-for="row in rows" :key="row.id">
          <td>{{ row.delegateName || row.delegateUserId }}</td><td>{{ time(row.startsAt) }}</td><td>{{ time(row.endsAt) }}</td>
          <td>{{ t(status[row.status]) }}</td>
          <td>
            <n-button text type="primary" @click="open(row.id)">
              {{ t('查看详情') }}
            </n-button>
          </td>
        </tr>
      </tbody>
    </n-table>
    <n-empty v-if="!rows.length" class="mt-12" :description="t('暂无流程转办记录')" />

    <n-modal v-if="current" :show="true" preset="card" :title="t('流程转办详情')" style="width:min(620px, calc(100vw - 32px))" @update:show="!$event && !saving && (current = undefined)">
      <n-descriptions :column="1">
        <n-descriptions-item :label="t('转办人')">
          {{ current.delegation.delegateName }}
        </n-descriptions-item>
        <n-descriptions-item :label="t('休假开始')">
          {{ time(current.delegation.startsAt) }}
        </n-descriptions-item>
        <n-descriptions-item :label="t('休假结束')">
          {{ time(current.delegation.endsAt) }}
        </n-descriptions-item>
        <n-descriptions-item :label="t('状态')">
          {{ t(status[current.delegation.status]) }}
        </n-descriptions-item>
      </n-descriptions>
      <n-divider>{{ t('已分派给转办人') }}</n-divider>
      <p v-for="todo in current.forwarded" :key="todo.todoId">
        {{ todo.title }} · WF-{{ todo.flowId }}
      </p>
      <n-divider>{{ t('可补发待办') }}</n-divider>
      <n-checkbox-group v-model:value="chosen">
        <n-space vertical>
          <n-checkbox v-for="todo in current.pending" :key="todo.todoId" :value="todo.todoId">
            {{ todo.title }} · WF-{{ todo.flowId }}
          </n-checkbox>
        </n-space>
      </n-checkbox-group>
      <n-empty v-if="!current.pending.length" :description="t('暂无可补发待办')" />
      <n-space justify="end" class="mt-16">
        <n-popconfirm v-if="['ACTIVE', 'SCHEDULED'].includes(current.delegation.status)" @positive-click="end">
          <template #trigger>
            <n-button secondary type="warning">
              {{ t('终止转办') }}
            </n-button>
          </template>
          {{ t('确认终止本次转办？') }}
        </n-popconfirm>
        <n-button :disabled="saving" @click="current = undefined">
          {{ t('关闭') }}
        </n-button>
        <n-button type="primary" :disabled="!chosen.length" :loading="saving" @click="forward">
          {{ t('补发所选') }}
        </n-button>
      </n-space>
    </n-modal>
  </n-card>
</template>

<script setup lang="ts">
import type { Delegation, DelegationDetail } from '@/views/workbench/workflow/api'
import { onMounted, ref } from 'vue'
import { t } from '@/i18n'
import api from '@/views/workbench/workflow/api'

const delegateUserId = ref<number | null>(null)
const startsAt = ref('')
const endsAt = ref('')
const rows = ref<Delegation[]>([])
const options = ref<{ value: number, label: string }[]>([])
const chosen = ref<number[]>([])
const current = ref<DelegationDetail>()
const saving = ref(false)
const searching = ref(false)
const status = {
  ACTIVE: '生效中',
  ENDED: '已结束',
  SCHEDULED: '待生效',
  CANCELLED: '已终止',
}
let latestSearch = 0

const time = (value: string) => value.replace('T', ' ').slice(0, 16)

async function search(keyword: string) {
  const id = ++latestSearch
  searching.value = true
  try {
    const { data } = await api.candidates(keyword)
    if (id === latestSearch) {
      const selected = options.value.find(item => item.value === delegateUserId.value)
      options.value = data.map(user => ({ value: user.id, label: user.name }))
      if (selected && !options.value.some(item => item.value === selected.value))
        options.value.push(selected)
    }
  }
  catch {
    // 请求错误由统一拦截器显示。
  }
  finally {
    if (id === latestSearch)
      searching.value = false
  }
}
async function load() {
  try {
    rows.value = (await api.delegations()).data
  }
  catch {
    // 请求错误由统一拦截器显示。
  }
}
async function open(id: number) {
  try {
    current.value = (await api.delegation(id)).data
    chosen.value = []
  }
  catch {
    // 请求错误由统一拦截器显示。
  }
}
async function save() {
  if (saving.value || !delegateUserId.value)
    return
  saving.value = true
  try {
    const { data } = await api.addDelegation({ delegateUserId: delegateUserId.value, startsAt: startsAt.value, endsAt: endsAt.value })
    await Promise.all([
      load(),
      open(data.id),
    ])
  }
  catch {
    // 请求错误由统一拦截器显示。
  }
  finally {
    saving.value = false
  }
}
async function forward() {
  if (saving.value || !current.value)
    return
  saving.value = true
  const id = current.value.delegation.id
  try {
    await api.forward(id, chosen.value)
    await open(id)
  }
  catch {
    // 请求错误由统一拦截器显示。
  }
  finally {
    saving.value = false
  }
}
async function end() {
  if (saving.value || !current.value)
    return
  saving.value = true
  const id = current.value.delegation.id
  try {
    await api.endDelegation(id)
    await Promise.all([
      load(),
      open(id),
    ])
  }
  catch {
    // 请求错误由统一拦截器显示。
  }
  finally {
    saving.value = false
  }
}
onMounted(() => Promise.all([
  search(''),
  load(),
]))
</script>

<style scoped>
.delegation-date {
  border: 1px solid #8886;
  border-radius: 4px;
  background: transparent;
  padding: 6px 9px;
  color: inherit;
}
</style>
