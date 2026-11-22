<template>
  <n-drawer
    :show="true"
    width="min(760px, 96vw)"
    :mask-closable="false"
    :close-on-esc="!sending && !picker"
    @update:show="close"
  >
    <n-drawer-content :title="title" :closable="!sending" native-scrollbar>
      <n-alert v-if="error" type="error" class="mb-12">
        {{ errorMessage(error, t('加载失败')) }}
        <n-button text @click="reload">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-spin :show="loading">
        <n-space v-if="ready" vertical :size="16">
          <n-descriptions v-if="readonly" :column="1" label-placement="left" bordered size="small">
            <n-descriptions-item :label="t('群组名称')">
              {{ name }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('说明')">
              <span class="whitespace-pre-wrap">{{ description || '—' }}</span>
            </n-descriptions-item>
            <n-descriptions-item :label="t('状态')">
              <n-tag size="small" :type="enabled ? 'success' : 'default'">
                {{ t(enabled ? '启用' : '停用') }}
              </n-tag>
            </n-descriptions-item>
          </n-descriptions>
          <n-form v-else label-placement="top" :disabled="sending" @submit.prevent="save">
            <n-form-item :label="t('群组名称')" required>
              <n-input v-model:value="name" :maxlength="100" :count-graphemes="countTrimmedCharacters" :input-props="{ 'aria-label': t('群组名称') }" />
            </n-form-item>
            <n-form-item :label="t('说明')">
              <n-input v-model:value="description" type="textarea" :maxlength="500" :count-graphemes="countTrimmedCharacters" :autosize="{ minRows: 2, maxRows: 5 }" :input-props="{ 'aria-label': t('说明') }" />
            </n-form-item>
          </n-form>

          <n-card size="small" :title="t('指定用户（{0}）', sources.users.length)">
            <template v-if="!readonly" #header-extra>
              <n-button size="small" :disabled="sending || sources.users.length >= MAX_USERS" @click="picker = 'users'">
                {{ t('添加用户') }}
              </n-button>
            </template>
            <n-empty v-if="!sources.users.length" :description="t('未指定用户')" />
            <n-scrollbar v-else style="max-height: 240px">
              <n-list hoverable>
                <n-list-item v-for="user in sources.users" :key="user.userId">
                  <div class="flex flex-wrap items-center gap-8 break-all">
                    <span>{{ userLabel(user) }}</span>
                    <span v-if="user.username && user.username !== userLabel(user)" class="opacity-65">{{ user.username }}</span>
                    <n-tag v-if="user.status !== 'ACTIVE'" size="small" type="warning">
                      {{ sourceStatusLabel(user.status) }}
                    </n-tag>
                  </div>
                  <template v-if="!readonly" #suffix>
                    <n-button size="small" quaternary :disabled="sending" :aria-label="t('移除 {0}', userLabel(user))" @click="removeUser(user.userId)">
                      {{ t('移除') }}
                    </n-button>
                  </template>
                </n-list-item>
              </n-list>
            </n-scrollbar>
          </n-card>

          <n-card size="small" :title="t('部门（{0}）', sources.departments.length)">
            <template v-if="!readonly" #header-extra>
              <n-button size="small" :disabled="sending || sources.departments.length >= MAX_DEPARTMENTS" @click="picker = 'departments'">
                {{ t('添加部门') }}
              </n-button>
            </template>
            <n-empty v-if="!sources.departments.length" :description="t('未选择部门')" />
            <n-scrollbar v-else style="max-height: 285px">
              <n-list hoverable>
                <n-list-item v-for="department in sources.departments" :key="department.departmentId">
                  <div class="flex flex-wrap items-center gap-8 break-all">
                    <span>{{ department.path || t('部门 ID {0}', department.departmentId) }}</span>
                    <n-tag size="small" :type="department.includeDescendants ? 'info' : 'default'">
                      {{ t(department.includeDescendants ? '包含下级部门' : '仅直属成员') }}
                    </n-tag>
                    <n-tag v-if="department.status !== 'ACTIVE'" size="small" type="warning">
                      {{ sourceStatusLabel(department.status) }}
                    </n-tag>
                  </div>
                  <n-checkbox
                    v-if="!readonly"
                    v-model:checked="department.includeDescendants"
                    :disabled="sending || department.status !== 'ACTIVE'"
                    :aria-label="t('{0}包含下级部门', department.path)"
                    class="mt-6"
                  >
                    {{ t('包含下级部门') }}
                  </n-checkbox>
                  <template v-if="!readonly" #suffix>
                    <n-button size="small" quaternary :disabled="sending" :aria-label="t('移除 {0}', department.path)" @click="removeDepartment(department.departmentId)">
                      {{ t('移除') }}
                    </n-button>
                  </template>
                </n-list-item>
              </n-list>
            </n-scrollbar>
          </n-card>

          <div v-if="showPreview" ref="previewPanel">
            <GroupRecipients :group-id="id" :sources="readonly ? undefined : sources" />
          </div>
          <GroupUsages v-if="id != null" :group-id="id" :editing="!readonly" />
        </n-space>
      </n-spin>

      <template #footer>
        <div class="w-full">
          <n-alert v-if="conflict" type="warning" class="mb-12">
            {{ t('配置已被他人更新。当前草稿已保留，请核对后重新加载已保存的配置。') }}
            <n-button text type="primary" :disabled="loading" @click="confirmReload">
              {{ t('重新加载已保存配置') }}
            </n-button>
          </n-alert>
          <n-alert v-else-if="saveError" type="error" class="mb-12">
            {{ saveError }}
          </n-alert>
          <n-space justify="end">
            <RouterLink v-if="canAudit && id != null" v-slot="{ href, navigate }" :to="auditLocation('NOTIFICATION_GROUP', id)" custom>
              <n-button tag="a" :href="href" :disabled="sending" @click="navigate">
                {{ t('查看操作记录') }}
              </n-button>
            </RouterLink>
            <n-button v-if="ready" :disabled="sending" @click="togglePreview">
              {{ showPreview ? t('收起预览') : t('收件人预览') }}
            </n-button>
            <n-button :disabled="sending" @click="emit('close')">
              {{ t(readonly ? '关闭' : '取消') }}
            </n-button>
            <n-button v-if="!readonly" type="primary" :loading="sending" :disabled="!ready || loading || conflict" @click="save">
              {{ t('保存') }}
            </n-button>
          </n-space>
        </div>
      </template>
    </n-drawer-content>
  </n-drawer>

  <PagedPicker
    v-if="picker === 'users'"
    :title="t('选择用户')"
    :placeholder="t('搜索姓名或账号')"
    :excluded="userIds"
    :limit="MAX_USERS - sources.users.length"
    :result-columns="userColumns"
    :load="loadUsers"
    @select="addUsers"
    @close="picker = null"
  />
  <DepartmentPicker
    v-if="picker === 'departments'"
    :excluded="departmentIds"
    :limit="MAX_DEPARTMENTS - sources.departments.length"
    @select="addDepartments"
    @close="picker = null"
  />
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { DepartmentSource, Recipient, SearchPage, Sources, UserSource } from './api'
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import api from './api'
import DepartmentPicker from './DepartmentPicker.vue'
import GroupRecipients from './GroupRecipients.vue'
import GroupUsages from './GroupUsages.vue'
import { auditLocation, confirmReplaceDraft, countTrimmedCharacters, errorMessage, errorReason, groupWrite, MAX_DEPARTMENTS, MAX_USERS, sourceStatusLabel } from './model'
import PagedPicker from './PagedPicker.vue'
import { useLatestResource } from './useLatestResource'

const props = defineProps<{ id?: number, readonly: boolean, canAudit: boolean }>()
const emit = defineEmits<{ close: [], saved: [] }>()

const title = computed(() => props.readonly
  ? t('通知群组详情')
  : props.id == null
    ? t('新建通知群组')
    : t('编辑通知群组'))
const name = ref('')
const description = ref('')
const enabled = ref(true)
const sources = reactive<Sources>({ users: [], departments: [] })
const version = ref(0)
const ready = ref(props.id == null)
const picker = ref<'users' | 'departments' | null>(null)
const showPreview = ref(false)
const previewPanel = ref<HTMLElement>()
const sending = ref(false)
const saveError = ref('')
const conflict = ref(false)
const userIds = computed(() => new Set(sources.users.map(row => row.userId)))
const departmentIds = computed(() => new Set(sources.departments.map(row => row.departmentId)))

const { data, loading, error, reload } = useLatestResource(() => {
  const { id } = props
  return id == null
    ? null
    : async signal => (await api.group(id, signal)).data
})

watch(data, (group) => {
  if (!group)
    return
  name.value = group.name
  description.value = group.description ?? ''
  enabled.value = group.enabled
  version.value = group.version
  sources.users = group.users.map(user => ({ ...user }))
  sources.departments = group.departments.map(department => ({ ...department }))
  conflict.value = false
  saveError.value = ''
  ready.value = true
})

function userLabel(user: UserSource | Recipient) {
  return displayName(user.nameCn || user.username, user.nameEn) || t('用户 ID {0}', user.userId)
}

type UserOption = Recipient & { id: number }
const userColumns = computed<DataTableColumns<UserOption>>(() => [
  {
    title: t('姓名'),
    key: 'name',
    render: userLabel,
    ellipsis: { tooltip: true },
  },
  {
    title: t('用户名'),
    key: 'username',
    ellipsis: { tooltip: true },
  },
])

async function loadUsers(params: SearchPage, signal: AbortSignal) {
  const { data } = await api.userOptions(params, signal)
  return {
    pageData: data.pageData.map(user => ({ ...user, id: user.userId })),
    total: data.total,
  }
}

function addUsers(users: UserOption[]) {
  sources.users.push(...users.map(({ userId, nameCn, nameEn, username }) => ({
    userId,
    nameCn,
    nameEn,
    username,
    status: 'ACTIVE' as const,
  })))
  picker.value = null
}

function addDepartments(departments: DepartmentSource[]) {
  sources.departments.push(...departments)
  picker.value = null
}

function removeUser(id: number) {
  sources.users = sources.users.filter(user => user.userId !== id)
}

function removeDepartment(id: number) {
  sources.departments = sources.departments.filter(department => department.departmentId !== id)
}

function close(show: boolean) {
  if (!show && !sending.value)
    emit('close')
}

async function togglePreview() {
  showPreview.value = !showPreview.value
  if (showPreview.value) {
    await nextTick()
    previewPanel.value?.scrollIntoView({ block: 'start' })
  }
}

function confirmReload() {
  confirmReplaceDraft(reload)
}

async function save() {
  if (props.readonly || !ready.value || sending.value || loading.value || conflict.value)
    return
  const write = groupWrite(name.value, description.value, sources)
  if (!write.name) {
    saveError.value = t('请输入群组名称')
    return
  }
  if (!write.userIds.length && !write.departments.length) {
    saveError.value = t('至少选择一名用户或一个部门。')
    return
  }

  sending.value = true
  saveError.value = ''
  try {
    if (props.id == null)
      await api.create(write)
    else
      await api.update(props.id, { ...write, expectedVersion: version.value })
    emit('saved')
  }
  catch (failure) {
    const reason = errorReason(failure)
    if (reason === 'VERSION_CONFLICT') {
      conflict.value = true
    }
    else {
      saveError.value = reason === 'NAME_CONFLICT'
        ? t('已存在同名通知群组。')
        : errorMessage(failure, t('保存失败'))
    }
  }
  finally {
    sending.value = false
  }
}
</script>
