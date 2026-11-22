<template>
  <NInput
    v-model:value="keyword"
    class="mb-12"
    :placeholder="t('搜索角色')"
    :aria-label="t('搜索角色')"
    :disabled="disabled"
    clearable
    @update:value="clearChecks"
  />
  <div class="role-transfer">
    <NCard size="small" class="min-w-0" :title="t('未分配角色')" :segmented="{ content: true }">
      <NScrollbar style="height: clamp(120px, 34vh, 280px)">
        <NCheckboxGroup v-model:value="checkedAvailable" :aria-label="t('未分配角色')" :disabled="disabled">
          <NCheckbox v-for="role in availableRoles" :key="role.id" :value="role.id" class="my-4 w-full break-words">
            {{ role.label }}
          </NCheckbox>
        </NCheckboxGroup>
        <NEmpty v-if="!availableRoles.length" size="small" class="my-16" :description="emptyDescription" />
      </NScrollbar>
    </NCard>

    <div class="flex flex-col self-center gap-8">
      <NButton size="small" type="primary" secondary :disabled="disabled || !checkedAvailable.length" @click="assignChecked">
        {{ t('移入') }} →
      </NButton>
      <NButton size="small" :disabled="disabled || !checkedAssigned.length" @click="removeChecked">
        ← {{ t('移出') }}
      </NButton>
    </div>

    <NCard size="small" class="min-w-0" :title="t('已有角色')" :segmented="{ content: true }">
      <NScrollbar style="height: clamp(120px, 34vh, 280px)">
        <NCheckboxGroup v-model:value="checkedAssigned" :aria-label="t('已有角色')" :disabled="disabled">
          <NCheckbox v-for="role in assignedRoles" :key="role.id" :value="role.id" class="my-4 w-full break-words">
            {{ role.label }}
          </NCheckbox>
        </NCheckboxGroup>
        <NEmpty v-if="!assignedRoles.length" size="small" class="my-16" :description="emptyDescription" />
      </NScrollbar>
    </NCard>
  </div>
</template>

<script setup lang="ts">
import { NButton, NCard, NCheckbox, NCheckboxGroup, NEmpty, NInput, NScrollbar } from 'naive-ui'
import { computed, ref } from 'vue'
import { t } from '@/i18n'

interface RoleOption {
  id: number
  label: string
}

const props = defineProps<{
  options: readonly RoleOption[]
  disabled?: boolean
}>()

const roleIds = defineModel<number[]>({ required: true })
const keyword = ref('')
const checkedAvailable = ref<number[]>([])
const checkedAssigned = ref<number[]>([])
const assignedIds = computed(() => new Set(roleIds.value))
const searchText = computed(() => keyword.value.trim().toLocaleLowerCase())
const visibleRoles = computed(() => props.options.filter(role => role.label.toLocaleLowerCase().includes(searchText.value)))
const availableRoles = computed(() => visibleRoles.value.filter(role => !assignedIds.value.has(role.id)))
const assignedRoles = computed(() => visibleRoles.value.filter(role => assignedIds.value.has(role.id)))
const emptyDescription = computed(() => t(searchText.value ? '没有匹配的角色' : '暂无角色'))

function clearChecks() {
  checkedAvailable.value = []
  checkedAssigned.value = []
}

function assignChecked() {
  roleIds.value = roleIds.value.concat(checkedAvailable.value)
  checkedAvailable.value = []
}

function removeChecked() {
  const removed = new Set(checkedAssigned.value)
  roleIds.value = roleIds.value.filter(id => !removed.has(id))
  checkedAssigned.value = []
}
</script>

<style scoped>
.role-transfer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  gap: clamp(4px, 1.5vw, 12px);
}
</style>
