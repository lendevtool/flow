<template>
  <n-drawer :show="true" width="min(640px, 96vw)" :mask-closable="false" :close-on-esc="!sending && !picker" @update:show="close">
    <n-drawer-content :title="t('编辑场景绑定')" :closable="!sending" native-scrollbar>
      <n-alert v-if="error" type="error" class="mb-12">
        {{ errorMessage(error, t('加载失败')) }}
        <n-button text @click="reload">
          {{ t('重试') }}
        </n-button>
      </n-alert>
      <n-spin :show="loading">
        <n-space v-if="scene" vertical :size="16">
          <n-descriptions :column="1" label-placement="left" size="small" bordered>
            <n-descriptions-item :label="t('场景名称')">
              {{ displayName(scene.nameCn, scene.nameEn) }}
            </n-descriptions-item>
            <n-descriptions-item :label="t('场景编码')">
              {{ sceneCode }}
            </n-descriptions-item>
          </n-descriptions>
          <n-card :title="t('已绑定群组（{0}）', groups.length)" size="small">
            <template #header-extra>
              <n-button size="small" :disabled="sending || groups.length >= MAX_GROUPS_PER_SCENE" @click="picker = true">
                {{ t('添加群组') }}
              </n-button>
            </template>
            <n-empty v-if="!groups.length" :description="t('此场景没有绑定通知群组')" />
            <n-list v-else hoverable>
              <n-list-item v-for="group in groups" :key="group.id">
                <div class="break-all">
                  {{ group.name }}
                </div>
                <template #suffix>
                  <n-button size="small" quaternary :disabled="sending" :aria-label="t('移除 {0}', group.name)" @click="removeGroup(group.id)">
                    {{ t('移除') }}
                  </n-button>
                </template>
              </n-list-item>
            </n-list>
          </n-card>
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
            <RouterLink v-if="canAudit" v-slot="{ href, navigate }" :to="auditLocation('NOTIFICATION_SCENE', sceneCode)" custom>
              <n-button tag="a" :href="href" :disabled="sending" @click="navigate">
                {{ t('查看操作记录') }}
              </n-button>
            </RouterLink>
            <n-button :disabled="sending" @click="emit('close')">
              {{ t('取消') }}
            </n-button>
            <n-popconfirm v-if="willClear" @positive-click="save">
              <template #trigger>
                <n-button type="primary" :loading="sending" :disabled="loading || conflict">
                  {{ t('保存') }}
                </n-button>
              </template>
              {{ t('清空绑定后，此场景将没有群组收件人。确定保存？') }}
            </n-popconfirm>
            <n-button v-else type="primary" :loading="sending" :disabled="!scene || loading || conflict" @click="save">
              {{ t('保存') }}
            </n-button>
          </n-space>
        </div>
      </template>
    </n-drawer-content>
  </n-drawer>

  <PagedPicker
    v-if="picker"
    :title="t('选择启用的通知群组')"
    :placeholder="t('搜索群组名称')"
    :excluded="selectedIds"
    :limit="MAX_GROUPS_PER_SCENE - groups.length"
    :result-columns="columns"
    :load="loadGroups"
    @select="addGroups"
    @close="picker = false"
  />
</template>

<script setup lang="ts">
import type { DataTableColumns } from 'naive-ui'
import type { GroupOption, SceneDetail, SearchPage } from './api'
import { computed, ref, shallowRef, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { t } from '@/i18n'
import { displayName } from '@/i18n/names'
import api from './api'
import { auditLocation, confirmReplaceDraft, errorMessage, errorReason, MAX_GROUPS_PER_SCENE } from './model'
import PagedPicker from './PagedPicker.vue'
import { useLatestResource } from './useLatestResource'

const props = defineProps<{ sceneCode: string, canAudit: boolean }>()
const emit = defineEmits<{ close: [], saved: [] }>()

const scene = shallowRef<SceneDetail | null>(null)
const groups = ref<GroupOption[]>([])
const picker = ref(false)
const sending = ref(false)
const saveError = ref('')
const conflict = ref(false)
const selectedIds = computed(() => new Set(groups.value.map(row => row.id)))
const willClear = computed(() => !!scene.value?.groups.length && groups.value.length === 0)

const { data, loading, error, reload } = useLatestResource(() => {
  const { sceneCode } = props
  return async signal => (await api.scene(sceneCode, signal)).data
})

watch(data, (detail) => {
  if (!detail)
    return
  scene.value = detail
  groups.value = [...detail.groups]
  conflict.value = false
  saveError.value = ''
})

const columns = computed<DataTableColumns<GroupOption>>(() => [
  {
    title: t('群组名称'),
    key: 'name',
    ellipsis: { tooltip: true },
  },
])

async function loadGroups(params: SearchPage, signal: AbortSignal) {
  return (await api.groupOptions(params, signal)).data
}

function addGroups(rows: GroupOption[]) {
  groups.value.push(...rows)
  picker.value = false
}

function removeGroup(id: number) {
  groups.value = groups.value.filter(group => group.id !== id)
}

function close(show: boolean) {
  if (!show && !sending.value)
    emit('close')
}

function confirmReload() {
  confirmReplaceDraft(reload)
}

async function save() {
  if (!scene.value || sending.value || loading.value || conflict.value)
    return

  sending.value = true
  saveError.value = ''
  try {
    await api.replaceSceneGroups(props.sceneCode, {
      expectedVersion: scene.value.version,
      groupIds: groups.value.map(group => group.id),
    })
    emit('saved')
  }
  catch (failure) {
    if (errorReason(failure) === 'VERSION_CONFLICT')
      conflict.value = true
    else
      saveError.value = errorMessage(failure, t('保存失败'))
  }
  finally {
    sending.value = false
  }
}
</script>
