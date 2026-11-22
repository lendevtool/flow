<template>
  <section class="mt-12 border-t border-gray-500/20 border-solid pt-12">
    <n-spin :show="loading">
      <n-alert v-if="failed" type="error">
        {{ t('无法读取 MFA 密钥轮转状态') }}
        <n-button text class="ml-8" @click="load()">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <template v-if="status">
        <n-alert v-if="status.status === 'FAILED'" type="error" class="mb-12">
          {{ t('绑定迁移未完成。保留新旧密钥，检查配置后重试。') }}
        </n-alert>
        <n-alert v-else-if="status.reminderDue && status.status !== 'RUNNING'" type="warning" class="mb-12">
          {{ t(status.overdue ? 'MFA 密钥已到轮转时间。' : 'MFA 密钥即将到轮转时间。') }}
          <span v-if="!status.canStart">{{ t('等待配置新的密钥版本。') }}</span>
        </n-alert>
        <n-descriptions :column="1" size="small" label-placement="left">
          <n-descriptions-item :label="t('当前密钥版本')">
            {{ status.activeKeyId || t('尚未启用密钥') }}
          </n-descriptions-item>
          <n-descriptions-item :label="t('预置目标版本')">
            {{ status.targetKeyId || '—' }}
          </n-descriptions-item>
          <n-descriptions-item :label="t('上次成功轮转')">
            {{ displayTime(status.lastCompletedAt) }}
          </n-descriptions-item>
          <n-descriptions-item :label="t('下次计划轮转')">
            {{ displayTime(status.nextRotationAt) }}
          </n-descriptions-item>
          <n-descriptions-item :label="t('迁移状态')">
            {{ status.activeKeyId ? t(stateLabels[status.status]) : '—' }}
          </n-descriptions-item>
          <n-descriptions-item :label="t('待迁移绑定')">
            {{ status.pendingBindings }}
          </n-descriptions-item>
        </n-descriptions>

        <n-popconfirm :positive-text="t('确定')" :negative-text="t('取消')" @positive-click="start">
          <template #trigger>
            <n-button class="mt-8" :disabled="!status.canStart" :loading="starting">
              {{ t(startLabel) }}
            </n-button>
          </template>
          {{ t('启用目标版本后会分批迁移现有绑定，请确认所有实例均可读取新旧密钥。') }}
        </n-popconfirm>
      </template>
    </n-spin>
  </section>
</template>

<script setup lang="ts">
import type { MfaRotationState, MfaRotationStatus } from './api'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { t } from '@/i18n'
import { formatDateTime } from '@/utils'
import api from './api'

const stateLabels: Record<MfaRotationState, string> = {
  IDLE: '等待轮转',
  RUNNING: '迁移中',
  COMPLETED: '迁移完成',
  FAILED: '迁移失败',
}
const status = ref<MfaRotationStatus>()
const startLabel = computed(() => {
  if (!status.value?.activeKeyId)
    return '启用首个版本'
  return status.value.status === 'FAILED'
    ? '重试迁移'
    : '开始轮转'
})
const loading = ref(false)
const failed = ref(false)
const starting = ref(false)
let pollTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false

function displayTime(value: string | null) {
  return value
    ? formatDateTime(value)
    : '—'
}

function apply(value: MfaRotationStatus) {
  status.value = value
  if (pollTimer)
    clearTimeout(pollTimer)
  if (!disposed && value.status === 'RUNNING') {
    pollTimer = setTimeout(load, 3000, true)
  }
}

async function load(passive = false) {
  if (!passive)
    loading.value = true
  try {
    apply((await api.getRotation()).data)
    failed.value = false
  }
  catch {
    failed.value = true
  }
  finally {
    loading.value = false
  }
}

async function start() {
  if (starting.value || !status.value?.canStart)
    return false
  starting.value = true
  try {
    apply((await api.startRotation()).data)
  }
  catch {
    return false
  }
  finally {
    starting.value = false
  }
}

onMounted(() => load())
onUnmounted(() => {
  disposed = true
  if (pollTimer)
    clearTimeout(pollTimer)
})
</script>
