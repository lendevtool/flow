<template>
  <n-drawer :show="!!personTarget" width="min(460px, 95vw)" placement="right" @update:show="onShow">
    <n-drawer-content :title="t('人员信息')" closable>
      <n-spin v-if="loading" class="my-24" :show="true">
        <p class="py-24 text-center">
          {{ personTarget?.label || t('正在加载人员信息') }}
        </p>
      </n-spin>
      <n-alert v-else-if="error" type="error">
        {{ error }}
        <n-button text @click="retry++">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <template v-else-if="person">
        <h2 class="m-0 text-20 font-medium">
          {{ displayName(person.nameCn || person.username, person.nameEn) }}
        </h2>
        <p class="mb-20 mt-4 opacity-65">
          {{ person.username }}
        </p>
        <n-descriptions label-placement="top" bordered :column="1">
          <n-descriptions-item :label="t('姓名')">
            {{ person.nameCn || '—' }}
          </n-descriptions-item>
          <n-descriptions-item :label="t('英文姓名')">
            {{ person.nameEn || '—' }}
          </n-descriptions-item>
          <n-descriptions-item :label="t('邮件')">
            <span class="break-all">{{ person.email || '—' }}</span>
          </n-descriptions-item>
          <n-descriptions-item :label="t('所属部门')">
            <div v-if="person.departments.length" class="flex flex-wrap gap-8">
              <n-tag v-for="department in person.departments" :key="department.id" size="small" :type="department.id === person.primaryDepartmentId ? 'primary' : 'default'">
                {{ department.name }} · {{ t(department.id === person.primaryDepartmentId ? '主部门' : '兼岗') }}
              </n-tag>
            </div>
            <span v-else>{{ t('未分配部门') }}</span>
          </n-descriptions-item>
          <n-descriptions-item :label="t('角色')">
            <div v-if="person.roles.length" class="flex flex-wrap gap-8">
              <n-tag v-for="role in person.roles" :key="role.id" size="small" :type="role.enable ? 'success' : 'default'">
                {{ resourceLabel(role) }}{{ role.enable ? '' : ` · ${t('停用')}` }}
              </n-tag>
            </div>
            <span v-else>{{ t('暂无角色') }}</span>
          </n-descriptions-item>
        </n-descriptions>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<script setup lang="ts">
import { ref, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import { resourceLabel } from '@/i18n/navigation'
import { useAuthStore } from '@/store'
import { request } from '@/utils'
import { closePersonDetails, personTarget } from './personDetails'

interface PersonDetails {
  username: string
  nameCn: string | null
  nameEn: string | null
  email: string | null
  primaryDepartmentId: number | null
  departments: { id: number, name: string }[]
  roles: { id: number, code: string, name: string, nameEn: string | null, enable: boolean }[]
}

const auth = useAuthStore()
const route = useRoute()
const person = shallowRef<PersonDetails>()
const error = ref('')
const loading = ref(false)
const retry = ref(0)

watch([
  () => route.fullPath,
  () => auth.accessToken,
], closePersonDetails)
watch([personTarget, retry], async ([target], _, onCleanup) => {
  person.value = undefined
  error.value = ''
  loading.value = !!target
  if (!target)
    return

  const controller = new AbortController()
  onCleanup(() => controller.abort())
  try {
    const config = { signal: controller.signal, needTip: false }
    const { data } = await request.get<unknown, { data: PersonDetails }>(`/user/${target.id}/person`, config)
    if (!controller.signal.aborted)
      person.value = data
  }
  catch (reason) {
    if (!controller.signal.aborted)
      error.value = (reason as { message?: string } | null)?.message || t('加载失败')
  }
  finally {
    if (!controller.signal.aborted)
      loading.value = false
  }
})

function onShow(show: boolean) {
  if (!show)
    closePersonDetails()
}
</script>
