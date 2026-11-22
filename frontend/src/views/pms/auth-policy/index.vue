<template>
  <CommonPage>
    <template #action>
      <n-space>
        <n-button :disabled="busy" @click="load">
          {{ t('刷新') }}
        </n-button>
        <n-button :disabled="!snapshot || busy || conflict" @click="confirmReset">
          {{ t('恢复默认值') }}
        </n-button>
        <n-button type="primary" :loading="saving" :disabled="!dirty || busy || conflict" @click="save">
          {{ t('保存') }}
        </n-button>
      </n-space>
    </template>

    <n-spin :show="loading">
      <n-alert v-if="loadFailed" type="error" class="mb-16">
        {{ t('读取策略失败，请刷新重试') }}
      </n-alert>
      <n-alert v-if="conflict" type="warning" class="mb-16">
        {{ t('策略已被修改，请刷新后重试') }}
      </n-alert>
      <template v-if="snapshot">
        <p class="mb-16 text-13 opacity-70">
          {{ t('密码规则在下次设密时应用，会话时长在新会话签发时确定。') }}
        </p>
        <div class="policy-groups">
          <n-card v-for="group in groups" :key="group.key" :title="t(group.title)" size="small">
            <n-table :bordered="false" :single-line="true" size="small">
              <thead>
                <tr>
                  <th>{{ t('配置项') }}</th>
                  <th>{{ t('当前值') }}</th>
                  <th>{{ t('默认值') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="field in group.fields" :key="field.key">
                  <th scope="row">
                    {{ t(field.label) }}
                  </th>
                  <td>
                    <n-switch
                      v-if="field.kind === 'switch'"
                      :value="draft[group.key]?.[field.key] === true"
                      :disabled="busy"
                      :aria-label="t(field.label)"
                      @update:value="setValue(group.key, field.key, $event)"
                    />
                    <n-checkbox-group
                      v-else-if="field.kind === 'characters'"
                      :value="selectedTypes(draft[group.key]?.[field.key])"
                      :disabled="busy"
                      :aria-label="t(field.label)"
                      @update:value="updateCharacters(group.key, field.key, $event)"
                    >
                      <n-space size="small">
                        <n-checkbox v-for="option in characterOptions" :key="option.value" :value="option.value">
                          {{ t(option.label) }}
                        </n-checkbox>
                      </n-space>
                    </n-checkbox-group>
                    <n-input-number
                      v-else
                      :value="numberValue(draft[group.key]?.[field.key], field.secondsPerUnit)"
                      :min="numberValue(field.min, field.secondsPerUnit) ?? undefined"
                      :max="numberValue(field.max, field.secondsPerUnit) ?? undefined"
                      :precision="field.secondsPerUnit ? durationPrecision : 0"
                      :format="field.secondsPerUnit ? formatDuration : undefined"
                      :show-button="!field.secondsPerUnit"
                      :class="{ 'policy-duration-input': field.secondsPerUnit }"
                      :disabled="busy"
                      :aria-label="t(field.label)"
                      @update:value="updateNumber(group.key, field, $event)"
                    >
                      <template v-if="field.unit" #suffix>
                        {{ t(field.unit) }}
                      </template>
                    </n-input-number>
                    <small v-if="field.hint" class="mt-4 block opacity-70">
                      {{ t(field.hint) }}
                    </small>
                  </td>
                  <td>{{ display(snapshot.defaults[group.key]?.[field.key], field) }}</td>
                </tr>
              </tbody>
            </n-table>
            <MfaKeyManagement v-if="group.key === 'mfa'" @published="rotationRefresh++" />
            <MfaRotationStatus v-if="group.key === 'mfa'" :key="rotationRefresh" />
          </n-card>
        </div>
      </template>
    </n-spin>
  </CommonPage>
</template>

<script setup lang="ts">
import type { DialogApi, MessageApi } from 'naive-ui'
import type { CharacterType, PolicySnapshot, PolicyValue } from './api'
import { computed, onMounted, ref } from 'vue'
import { t } from '@/i18n'
import api from './api'
import MfaKeyManagement from './MfaKeyManagement.vue'
import MfaRotationStatus from './MfaRotationStatus.vue'

defineOptions({ name: 'AuthPolicyMgt' })

const notifications: Window & {
  $message?: Pick<MessageApi, 'warning' | 'success'>
  $dialog?: Pick<DialogApi, 'warning'>
} = window

interface Field {
  key: string
  label: string
  kind: 'number' | 'switch' | 'characters'
  min?: number
  max?: number
  unit?: string
  secondsPerUnit?: number
  hint?: string
}

const maxSeconds = 2147483647
const durationPrecision = 6
const groups: { key: string, title: string, fields: Field[] }[] = [
  {
    key: 'mfa',
    title: '多因素认证',
    fields: [
      {
        key: 'required',
        label: '全局强制 MFA',
        kind: 'switch',
        hint: '开启后，本地和域登录均须验证，已有未验证会话须重新登录。',
      },
      {
        key: 'keyRotationDays',
        label: 'MFA 密钥轮转周期',
        kind: 'number',
        min: 1,
        max: 3650,
        unit: '天',
      },
      {
        key: 'keyReminderDays',
        label: 'MFA 密钥到期提前提醒',
        kind: 'number',
        min: 0,
        max: 3649,
        unit: '天',
        hint: '0 表示在到期当天提醒。',
      },
    ],
  },
  {
    key: 'captcha',
    title: '图形验证码',
    fields: [
      {
        key: 'required',
        label: '全局图形验证码',
        kind: 'switch',
        hint: '开启后，本地登录和域登录都需要图形验证码。',
      },
      {
        key: 'ttlSeconds',
        label: '验证码有效期',
        kind: 'number',
        min: 1,
        max: maxSeconds,
        unit: '秒',
      },
    ],
  },
  {
    key: 'login',
    title: '登录方式',
    fields: [
      { key: 'localEnabled', label: '允许本地登录', kind: 'switch' },
      {
        key: 'domainEnabled',
        label: '允许域登录',
        kind: 'switch',
        hint: '使用微软 Active Directory 域账号',
      },
    ],
  },
  {
    key: 'password',
    title: '密码规则',
    fields: [
      {
        key: 'minLength',
        label: '密码最低长度',
        kind: 'number',
        min: 8,
        max: 20,
        unit: '位',
      },
      { key: 'requiredCharacterTypes', label: '密码复杂度', kind: 'characters' },
      { key: 'rejectUsername', label: '禁止密码与账号名相同', kind: 'switch' },
      { key: 'rejectCurrent', label: '禁止重复使用当前密码', kind: 'switch' },
      {
        key: 'maxAgeSeconds',
        label: '正式密码最长使用时长',
        kind: 'number',
        min: 0,
        max: maxSeconds,
        unit: '天',
        secondsPerUnit: 86400,
        hint: '0 表示不限',
      },
      {
        key: 'temporaryTtlSeconds',
        label: '临时密码有效期',
        kind: 'number',
        min: 1,
        max: maxSeconds,
        unit: '天',
        secondsPerUnit: 86400,
        hint: '须大于 0',
      },
    ],
  },
  {
    key: 'bootstrap',
    title: '初始化管理员',
    fields: [
      { key: 'requirePasswordChange', label: '初始化管理员首次登录须换密', kind: 'switch' },
    ],
  },
  {
    key: 'session',
    title: '会话规则',
    fields: [
      {
        key: 'idleTimeoutSeconds',
        label: '会话空闲时长',
        kind: 'number',
        min: 1,
        max: maxSeconds,
        unit: '分钟',
        secondsPerUnit: 60,
      },
      {
        key: 'absoluteTimeoutSeconds',
        label: '会话最长时长',
        kind: 'number',
        min: 1,
        max: maxSeconds,
        unit: '小时',
        secondsPerUnit: 3600,
      },
      { key: 'allowConcurrent', label: '允许并行登录', kind: 'switch' },
    ],
  },
  {
    key: 'rateLimit',
    title: '认证限流',
    fields: [
      { key: 'enabled', label: '启用认证限流', kind: 'switch' },
      {
        key: 'windowSeconds',
        label: '限流窗口',
        kind: 'number',
        min: 1,
        max: 3600,
        unit: '秒',
      },
      {
        key: 'loginPerIp',
        label: '每 IP 登录次数',
        kind: 'number',
        min: 1,
        max: 100000,
      },
      {
        key: 'loginPerAccount',
        label: '每账号登录次数',
        kind: 'number',
        min: 1,
        max: 100000,
      },
      {
        key: 'captchaPerIp',
        label: '每 IP 验证码申请次数',
        kind: 'number',
        min: 1,
        max: 100000,
      },
      {
        key: 'changePasswordPerAccount',
        label: '每账号旧密码验证次数',
        kind: 'number',
        min: 1,
        max: 100000,
      },
    ],
  },
]
const characterOptions: { value: CharacterType, label: string }[] = [
  { value: 'uppercase', label: '大写字母' },
  { value: 'lowercase', label: '小写字母' },
  { value: 'digit', label: '数字' },
  { value: 'special', label: '特殊字符' },
]

const snapshot = ref<PolicySnapshot>()
const rotationRefresh = ref(0)
const draft = ref<Record<string, Record<string, PolicyValue | null>>>({})
const loading = ref(false)
const saving = ref(false)
const loadFailed = ref(false)
const conflict = ref(false)
const busy = computed(() => loading.value || saving.value)
const dirty = computed(() => Boolean(snapshot.value) && JSON.stringify(draft.value) !== JSON.stringify(snapshot.value?.config))

function numberValue(value: PolicyValue | null | undefined, secondsPerUnit = 1) {
  return typeof value === 'number'
    ? Number((value / secondsPerUnit).toFixed(durationPrecision))
    : null
}

function formatDuration(value: number | null) {
  return value === null
    ? ''
    : String(value)
}

function selectedTypes(value: PolicyValue | null | undefined) {
  return Array.isArray(value)
    ? value
    : []
}

function setValue(group: string, key: string, value: PolicyValue | null) {
  draft.value[group][key] = value
}

function updateNumber(group: string, field: Field, value: number | null) {
  // 表单只在用户修改时换算，刷新或保存其他字段不会改动原有秒数。
  const stored = value === null
    ? null
    : Math.round(value * (field.secondsPerUnit ?? 1))
  setValue(group, field.key, stored)
}

function updateCharacters(group: string, key: string, values: (string | number)[]) {
  setValue(group, key, characterOptions.map(option => option.value).filter(value => values.includes(value)))
}

function display(value: PolicyValue | undefined, field: Field) {
  if (Array.isArray(value))
    return characterOptions.filter(option => value.includes(option.value)).map(option => t(option.label)).join(' / ') || '—'
  if (typeof value === 'boolean')
    return t(value ? '开启' : '关闭')
  const number = numberValue(value, field.secondsPerUnit)
  if (number === null)
    return '—'
  if (number === 0 && field.secondsPerUnit && field.min === 0)
    return t('0 天（不限）')
  return field.unit
    ? `${number} ${t(field.unit)}`
    : String(number)
}

function apply(next: PolicySnapshot) {
  snapshot.value = next
  rotationRefresh.value++
  draft.value = Object.fromEntries(Object.entries(next.config).map(([key, values]) => [
    key,
    { ...values },
  ]))
  conflict.value = false
}

async function load() {
  if (busy.value)
    return
  loading.value = true
  loadFailed.value = false
  try {
    apply((await api.get()).data)
  }
  catch {
    loadFailed.value = true
  }
  finally {
    loading.value = false
  }
}

function buildConfig() {
  const config: PolicySnapshot['config'] = {}
  for (const [key, values] of Object.entries(draft.value)) {
    const fields: Record<string, PolicyValue> = {}
    for (const [field, value] of Object.entries(values)) {
      if (value === null)
        return null
      fields[field] = value
    }
    config[key] = fields
  }
  return config
}

async function save() {
  const config = buildConfig()
  if (!config) {
    notifications.$message?.warning(t('请填写所有配置项'))
    return
  }
  if (!config.login.localEnabled && !config.login.domainEnabled) {
    notifications.$message?.warning(t('至少保留一种登录方式'))
    return
  }
  if (Number(config.session.idleTimeoutSeconds) >= Number(config.session.absoluteTimeoutSeconds)) {
    notifications.$message?.warning(t('会话空闲时长必须小于会话最长时长'))
    return
  }
  if (Number(config.mfa.keyReminderDays) >= Number(config.mfa.keyRotationDays)) {
    notifications.$message?.warning(t('提前提醒天数必须小于轮转周期'))
    return
  }
  await submit(revision => api.save(revision, config))
}

function confirmReset() {
  notifications.$dialog?.warning({
    title: t('恢复默认值'),
    content: t('确定将认证安全策略恢复为默认值？'),
    positiveText: t('确定'),
    negativeText: t('取消'),
    onPositiveClick: () => submit(api.reset),
  })
}

async function submit(action: (revision: number) => ReturnType<typeof api.reset>) {
  if (!snapshot.value || busy.value || conflict.value)
    return
  saving.value = true
  try {
    apply((await action(snapshot.value.revision)).data)
    notifications.$message?.success(t('保存成功'))
  }
  catch (error) {
    conflict.value = Boolean(error && typeof error === 'object' && 'code' in error && error.code === 409)
  }
  finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.policy-groups {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 520px), 1fr));
  gap: 16px;
}
.policy-groups th:first-child {
  width: 32%;
}
.policy-groups th:last-child {
  width: 24%;
}
.policy-duration-input {
  min-width: 96px;
}
</style>
