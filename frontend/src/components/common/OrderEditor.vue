<template>
  <n-modal v-model:show="show" preset="card" :title="title" :style="{ width: '620px', maxWidth: '94vw' }" :mask-closable="!saving" :close-on-esc="!saving" :closable="!saving">
    <div class="mb-12 text-13 opacity-60">
      {{ description }}
    </div>
    <div class="sort-list">
      <div
        v-for="(row, index) in rows" :key="row.id" class="sort-row"
        :class="{ dragging: dragIndex === index }" :draggable="!saving"
        @dragstart="startDrag($event, index)" @dragend="dragIndex = null"
        @dragover.prevent @drop.prevent="drop(index)"
      >
        <i class="i-fe:menu shrink-0 opacity-40" />
        <span class="w-24 shrink-0 opacity-50">{{ index + 1 }}</span>
        <div class="min-w-0 flex-1">
          <div class="truncate" :title="row.label">
            {{ row.label }}
          </div>
          <div class="truncate text-12 opacity-50">
            {{ row.detail }}
          </div>
        </div>
        <n-button size="small" quaternary :aria-label="t('上移')" :disabled="saving || index === 0" @click="move(index, index - 1)">
          <i class="i-fe:arrow-up" />
        </n-button>
        <n-button size="small" quaternary :aria-label="t('下移')" :disabled="saving || index === rows.length - 1" @click="move(index, index + 1)">
          <i class="i-fe:arrow-down" />
        </n-button>
      </div>
    </div>
    <template #footer>
      <div class="flex justify-end gap-12">
        <n-button :disabled="saving" @click="show = false">
          {{ t('取消') }}
        </n-button>
        <n-button type="primary" :loading="saving" @click="emit('save')">
          {{ t('保存排序') }}
        </n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup lang="ts">
import { t } from '@/i18n'

interface OrderRow {
  id: number
  label: string
  detail: string
}

const props = defineProps<{
  title: string
  description: string
  saving: boolean
}>()
const emit = defineEmits<{ save: [] }>()
const show = defineModel<boolean>('show', { required: true })
const rows = defineModel<OrderRow[]>('rows', { required: true })
const dragIndex = ref<number | null>(null)

watch(show, () => {
  dragIndex.value = null
})

function move(from: number, to: number) {
  if (props.saving || from === to || to < 0 || to >= rows.value.length)
    return
  const reordered = [...rows.value]
  const [row] = reordered.splice(from, 1)
  reordered.splice(to, 0, row)
  rows.value = reordered
}

function startDrag(event: DragEvent, index: number) {
  event.dataTransfer?.setData('text/plain', String(index))
  dragIndex.value = index
}

function drop(index: number) {
  if (dragIndex.value !== null)
    move(dragIndex.value, index)
  dragIndex.value = null
}
</script>

<style scoped>
.sort-list {
  max-height: 56vh;
  overflow-y: auto;
}

.sort-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 4px;
  border-bottom: 1px solid #8882;
  cursor: grab;
}

.sort-row.dragging {
  opacity: 0.4;
}
</style>
