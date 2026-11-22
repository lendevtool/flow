<template>
  <div ref="cell" class="table-actions" :class="{ 'table-actions--tiny': size === 'tiny' }" @click.stop>
    <div v-if="dropdown !== true" ref="buttons" class="table-actions__buttons" :class="{ 'table-actions__buttons--hidden': useDropdown }">
      <NTooltip v-for="action in actions" :key="action.key" :disabled="!iconOnly">
        <template #trigger>
          <NButton
            :size="size"
            :type="action.type"
            :secondary="action.secondary"
            :disabled="action.disabled"
            :loading="action.loading"
            :aria-label="iconOnly ? action.label : undefined"
            @click="action.onClick?.()"
          >
            <template v-if="action.icon" #icon>
              <i class="table-actions__icon" :class="action.icon" />
            </template>
            {{ iconOnly ? '' : action.label }}
          </NButton>
        </template>
        {{ action.label }}
      </NTooltip>
    </div>
    <NDropdown
      v-if="useDropdown"
      trigger="click"
      placement="bottom-end"
      :options="menuOptions"
      @select="selectAction"
    >
      <NButton :size="size" secondary :aria-label="t('操作')" :title="t('操作')" aria-haspopup="menu">
        <template #icon>
          <i class="table-actions__icon i-fe:more-horizontal" />
        </template>
      </NButton>
    </NDropdown>
  </div>
</template>

<script setup lang="ts">
import type { ButtonProps, DropdownOption } from 'naive-ui'
import { useResizeObserver } from '@vueuse/core'
import { NButton, NDropdown, NTooltip } from 'naive-ui'
import { computed, h, onMounted, ref } from 'vue'
import { t } from '@/i18n'

interface TableAction {
  key: string
  label: string
  icon?: string
  type?: ButtonProps['type']
  secondary?: boolean
  disabled?: boolean
  loading?: boolean
  onClick?: () => void
}

const props = withDefaults(defineProps<{
  actions: readonly TableAction[]
  iconOnly?: boolean
  size?: ButtonProps['size']
  /** 由表格统一控制菜单和列宽时传入，省略时根据单元格宽度自动折叠。 */
  dropdown?: boolean
}>(), { dropdown: undefined, size: 'small' })

const cell = ref<HTMLDivElement>()
const buttons = ref<HTMLDivElement>()
const autoDropdown = ref(false)
const useDropdown = computed(() => props.dropdown ?? autoDropdown.value)

function updateLayout() {
  if (props.dropdown !== undefined)
    return
  const availableWidth = cell.value?.getBoundingClientRect().width ?? 0
  if (availableWidth > 0 && buttons.value)
    autoDropdown.value = buttons.value.getBoundingClientRect().width > availableWidth
}

// 保留按钮的自然宽度，以便空间变宽或文案变化后恢复行内显示。
onMounted(updateLayout)
useResizeObserver([cell, buttons], updateLayout)

const menuOptions = computed<DropdownOption[]>(() => props.actions.map(action => ({
  key: action.key,
  label: action.label,
  disabled: action.disabled || action.loading,
  icon: action.icon
    ? () => h('i', { class: action.icon })
    : undefined,
})))

function selectAction(key: string) {
  const action = props.actions.find(item => item.key === key)
  if (action && !action.disabled && !action.loading)
    action.onClick?.()
}
</script>

<style scoped>
.table-actions {
  position: relative;
}

.table-actions__buttons {
  display: inline-flex;
  width: max-content;
  vertical-align: middle;
  gap: 12px;
}

.table-actions--tiny .table-actions__icon {
  font-size: var(--n-font-size);
}

.table-actions__buttons--hidden {
  position: absolute;
  inset-inline-end: 0;
  visibility: hidden;
  pointer-events: none;
}
</style>
