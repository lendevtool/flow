<template>
  <n-modal v-model:show="show" preset="card" :title="t('组织人员树')" style="width: min(720px, 94vw)">
    <n-tabs v-model:value="activeTab" type="line">
      <n-tab-pane v-for="tab in tabs" :key="tab.name" :name="tab.name" :tab="t(tab.label)" :tab-props="tabProps(tab.name)">
        <n-alert v-if="failed" type="error">
          {{ t('加载失败') }}
          <n-button text @click="retry++">
            {{ t('重试') }}
          </n-button>
        </n-alert>
        <n-spin v-else :show="loading">
          <n-input
            v-model:value="keyword"
            clearable
            :disabled="loading"
            :aria-label="t('搜索姓名、账号或部门')"
            :placeholder="t('搜索姓名、账号或部门')"
            class="mb-12"
          />
          <div class="organization-tree">
            <n-tree
              v-if="!loading"
              block-line
              virtual-scroll
              :data="trees[tab.name]"
              :default-expanded-keys="trees[tab.name].map(node => node.key)"
              :pattern="keyword.trim()"
              :filter="matches"
              :show-irrelevant-nodes="false"
              :render-prefix="renderPrefix"
              :render-suffix="renderSuffix"
              :selectable="false"
              class="organization-tree__nodes"
            />
          </div>
        </n-spin>
      </n-tab-pane>
    </n-tabs>
  </n-modal>
</template>

<script setup lang="ts">
import type { TreeOption } from 'naive-ui'
import type { Department, Member } from './api'
import { NTag } from 'naive-ui'
import { computed, h, ref, watch } from 'vue'
import { t } from '@/i18n'
import api from './api'
import { buildOrganizationTrees } from './organizationTrees'

const tabs = [
  { name: 'organization', label: '组织人员树' },
  { name: 'people', label: '人员树' },
] as const
type TabName = (typeof tabs)[number]['name']
const show = defineModel<boolean>('show', { required: true })
const activeTab = ref<TabName>('organization')
const keyword = ref('')
const retry = ref(0)
const loading = ref(false)
const failed = ref(false)
const departments = ref<Department[]>([])
const members = ref<Member[]>([])
const trees = computed(() => buildOrganizationTrees(departments.value, members.value))

async function readDirectory(signal: AbortSignal) {
  const pageSize = 100
  const all = new Map<number, Member>()
  for (let pageNo = 1; !signal.aborted; pageNo++) {
    const { data } = await api.candidates({ pageNo, pageSize }, signal)
    for (const member of data.pageData)
      all.set(member.id, member)
    if (!data.pageData.length || pageNo * pageSize >= data.total)
      break
  }
  return [
    ...all.values(),
  ]
}

watch(show, (open) => {
  if (open) {
    activeTab.value = 'organization'
    keyword.value = ''
  }
})

watch(activeTab, () => {
  keyword.value = ''
})

watch([show, retry], async ([open], _, onCleanup) => {
  if (!open)
    return
  const controller = new AbortController()
  const { signal } = controller
  onCleanup(() => controller.abort())
  loading.value = true
  failed.value = false
  try {
    const [{ data }, directory] = await Promise.all([
      api.list(signal),
      readDirectory(signal),
    ])
    if (!signal.aborted) {
      departments.value = data
      members.value = directory
    }
  }
  catch {
    if (!signal.aborted) {
      failed.value = true
      loading.value = false
      controller.abort()
    }
  }
  finally {
    if (!signal.aborted)
      loading.value = false
  }
}, { immediate: true })

function matches(pattern: string, option: TreeOption) {
  const query = pattern.toLocaleLowerCase()
  return Boolean(option.label?.toLocaleLowerCase().includes(query)
    || (typeof option.username === 'string' && option.username.toLocaleLowerCase().includes(query)))
}

function tabProps(name: TabName) {
  return {
    'role': 'button',
    'tabindex': 0,
    'aria-pressed': activeTab.value === name,
    'onKeydown': (event: KeyboardEvent) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault()
        activeTab.value = name
      }
    },
  }
}

function renderPrefix({ option }: { option: TreeOption }) {
  return h('i', {
    'class': option.userId == null
      ? 'i-material-symbols:folder-outline'
      : 'i-material-symbols:person-outline',
    'aria-hidden': 'true',
  })
}

function renderSuffix({ option }: { option: TreeOption }) {
  const tags = []
  if (option.leader)
    tags.push(h(NTag, { size: 'small', type: 'info' }, () => t('负责人')))
  if (option.inactive)
    tags.push(h(NTag, { size: 'small' }, () => t('停用')))
  return tags.length
    ? h('span', { class: 'flex shrink-0 items-center gap-6' }, tags)
    : null
}
</script>

<style scoped>
.organization-tree,
.organization-tree__nodes {
  height: min(52vh, 500px);
}
</style>
