<template>
  <n-modal v-model:show="show" preset="card" :title="title" style="width: min(620px, 94vw)" :mask-closable="!saving" :closable="!saving" :close-on-esc="!saving">
    <n-space vertical :size="12">
      <div v-if="departmentId != null" class="flex items-center gap-8">
        <n-switch
          :value="departmentOnly"
          :aria-label="t('本部门')"
          :disabled="saving"
          @update:value="changeScope"
        />
        <span>{{ departmentOnly ? t('本部门') : t('全公司') }}</span>
      </div>
      <n-input :value="keyword" clearable :maxlength="100" :disabled="saving" :placeholder="t('搜索姓名或账号')" @update:value="changeKeyword" />
      <n-alert v-if="failed" type="error">
        {{ t('加载失败') }}
        <n-button text @click="reload++">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-data-table
        :columns="columns"
        :data="rows"
        :row-key="row => row.id"
        :checked-row-keys="selected"
        :loading="loading"
        allow-checking-not-loaded
        size="small"
        @update:checked-row-keys="updateSelected"
      />
      <n-pagination v-model:page="page" :item-count="total" :page-size="20" :disabled="saving" />
    </n-space>
    <template #footer>
      <n-space justify="end">
        <n-button :disabled="saving" @click="show = false">
          {{ t('取消') }}
        </n-button>
        <n-button type="primary" :loading="saving" :disabled="loading || failed || !selected.length" @click="submit">
          {{ single ? t('确定') : t('确定（{0} 人）', selected.length) }}
        </n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import type { DataTableColumns, DataTableRowKey } from 'naive-ui'
import type { Member, VersionedUser } from './api'
import { computed, h, ref, watch } from 'vue'
import PersonLink from '@/components/common/PersonLink.vue'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import api from './api'

const props = defineProps<{
  title: string
  single?: boolean
  departmentId?: number
  leaderId?: number | null
  save: (users: VersionedUser[]) => Promise<void>
}>()

const show = defineModel<boolean>('show', { required: true })
const rows = ref<Member[]>([])
const selected = ref<number[]>([])
const keyword = ref('')
const page = ref(1)
const departmentOnly = ref(true)
const scopeId = computed(() => departmentOnly.value ? props.departmentId : undefined)
const reload = ref(0)
const total = ref(0)
const loading = ref(false)
const failed = ref(false)
const saving = ref(false)
const selectedVersions = new Map<number, number>()
let preselectLeader = true

const columns = computed<DataTableColumns<Member>>(() => [
  { type: 'selection', multiple: !props.single },
  {
    title: t('姓名'),
    key: 'name',
    ellipsis: { tooltip: true },
    render: row => h(PersonLink, {
      userId: row.id,
      label: displayName(row.nameCn || row.username, row.nameEn),
    }),
  },
  {
    title: t('用户名'),
    key: 'username',
    render: row => h(PersonLink, { userId: row.id, label: row.username }),
    ellipsis: { tooltip: true },
  },
  {
    title: t('主部门'),
    key: 'departmentName',
    ellipsis: { tooltip: true },
    render: row => row.departmentName || t('未分配部门'),
  },
])

function resetSelection() {
  selectedVersions.clear()
  selected.value = []
  preselectLeader = true
}

function changeKeyword(value: string) {
  keyword.value = value
  page.value = 1
}

function changeScope(value: boolean) {
  departmentOnly.value = value
  page.value = 1
  resetSelection()
}

watch(show, (open) => {
  if (open) {
    resetSelection()
    departmentOnly.value = true
    keyword.value = ''
    page.value = 1
  }
}, { immediate: true, flush: 'sync' })

watch([show, keyword, page, scopeId, reload], ([open, term, pageNo, departmentId], [wasOpen, oldTerm, , oldDepartmentId], onCleanup) => {
  if (!open)
    return
  const controller = new AbortController()
  const changedScope = departmentId !== oldDepartmentId
  const changedKeyword = term !== oldTerm
  rows.value = []
  if (!wasOpen || changedScope || changedKeyword)
    total.value = 0
  loading.value = true
  failed.value = false

  const timer = window.setTimeout(async () => {
    try {
      const fetchPage = departmentId == null
        ? api.candidates
        : api.members
      const { data } = await fetchPage({
        departmentId,
        keyword: term.trim(),
        enable: props.single ? true : undefined,
        pageNo,
        pageSize: 20,
      }, controller.signal)
      if (controller.signal.aborted)
        return
      rows.value = data.pageData
      total.value = data.total
      const leader = preselectLeader && data.pageData.find(row => row.id === props.leaderId)
      if (leader)
        updateSelected([leader.id])
    }
    catch {
      if (!controller.signal.aborted)
        failed.value = true
    }
    finally {
      if (!controller.signal.aborted)
        loading.value = false
    }
  }, wasOpen && changedKeyword && !changedScope
    ? 200
    : 0)
  onCleanup(() => {
    window.clearTimeout(timer)
    controller.abort()
  })
}, { immediate: true })

function updateSelected(keys: DataTableRowKey[]) {
  if (saving.value)
    return
  const ids = keys.filter((key): key is number => typeof key === 'number')
  if (ids.length > 100) {
    window.$message.warning(t('每次最多选择 100 人'))
    return
  }
  preselectLeader = false
  for (const id of selectedVersions.keys()) {
    if (!ids.includes(id))
      selectedVersions.delete(id)
  }
  for (const row of rows.value) {
    if (ids.includes(row.id) && !selectedVersions.has(row.id))
      selectedVersions.set(row.id, row.departmentVersion)
  }
  selected.value = ids
}

async function submit() {
  saving.value = true
  try {
    await props.save(selected.value.map(userId => ({
      userId,
      expectedVersion: selectedVersions.get(userId) ?? 0,
    })))
    show.value = false
  }
  catch (error) {
    if ((error as { code?: number } | null)?.code === 409) {
      selected.value = []
      selectedVersions.clear()
      reload.value++
    }
  }
  finally {
    saving.value = false
  }
}
</script>
