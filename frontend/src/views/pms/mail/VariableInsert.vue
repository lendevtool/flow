<template>
  <n-popover v-model:show="show" trigger="click" placement="bottom-start">
    <template #trigger>
      <n-button size="small" quaternary :disabled="disabled">
        {{ t('插入变量') }}
      </n-button>
    </template>
    <div class="max-w-70vw w-265">
      <n-auto-complete
        v-model:value="entry"
        :options="options"
        :placeholder="t('选择或输入变量名')"
        :input-props="{ 'aria-label': t('变量名') }"
        @select="entry = $event"
      />
      <n-button type="primary" size="small" class="mt-8" :disabled="!valid" @click="insert">
        {{ t('插入') }}
      </n-button>
    </div>
  </n-popover>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { t } from '@/i18n'
import { isVariableName } from './templateVariables'

const props = defineProps<{ names: string[], disabled?: boolean }>()
const emit = defineEmits<{ insert: [text: string] }>()
const show = ref(false)
const entry = ref('')
const names = computed(() => props.names.filter(name => name.includes(entry.value.trim())))
const options = computed(() => names.value.map(value => ({ value, label: value })))
const valid = computed(() => isVariableName(entry.value.trim()))

function insert() {
  if (!valid.value)
    return
  show.value = false
  emit('insert', `{{${entry.value.trim()}}}`)
  entry.value = ''
}
</script>
