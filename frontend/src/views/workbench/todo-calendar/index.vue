<template>
  <CommonPage :title="t('待办日历')">
    <template #action>
      <n-button v-if="can('WorkbenchTodoCreate')" type="primary" @click="editor = {}">
        <i class="i-material-symbols:add mr-4 text-18" aria-hidden="true" />
        {{ t('新建待办') }}
      </n-button>
    </template>

    <TodoCalendarNavigation v-model:cursor="cursor" v-model:period="period" />

    <n-form inline class="mb-12 flex-wrap" :show-feedback="false">
      <n-form-item :label="t('待办范围')">
        <n-select v-model:value="scope" class="min-w-144" :options="scopeOptions" />
      </n-form-item>
      <n-form-item :label="t('来源')">
        <n-select v-model:value="origin" class="min-w-138" clearable :options="originOptions" :placeholder="t('全部来源')" />
      </n-form-item>
      <n-form-item>
        <n-checkbox v-model:checked="includeCompleted">
          {{ t('含已完成') }}
        </n-checkbox>
      </n-form-item>
      <n-form-item v-if="router.hasRoute('WorkbenchTodo')">
        <n-button text type="primary" @click="openUnscheduled">
          {{ t('未排期待办') }}
        </n-button>
      </n-form-item>
    </n-form>
    <n-alert v-if="publicRequest.error.value || personalRequest.error.value" type="error" class="mb-12">
      {{ publicRequest.error.value || personalRequest.error.value }}
      <n-button text @click="reload">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <WorkbenchDimensions v-model="dimensions" class="mb-16" :public-dimensions="publicDimensions" :personal-dimensions="personalDimensions" />
    <n-alert v-if="calendar.error.value" type="error" class="mb-12">
      {{ calendar.error.value }}
      <n-button text @click="loadCalendar">
        {{ t('重试') }}
      </n-button>
    </n-alert>

    <TodoCalendarGrid
      :cursor="cursor"
      :period="period"
      :items="calendar.data.value"
      :loading="calendar.loading.value"
      :date-label="dateLabel"
      @select-date="pickDate"
      @open-day="openDay"
      @open-item="todo => selected = todo.id"
    />

    <n-modal :show="!!day" preset="card" :title="day ?? ''" class="max-w-95vw w-520" @update:show="show => !show && (day = null)">
      <n-list v-if="dayTodos.length">
        <n-list-item v-for="todo in dayTodos.slice((dayPage - 1) * 10, dayPage * 10)" :key="todo.id">
          <n-button text type="primary" class="max-w-full" @click="openDayTodo(todo.id)">
            <n-ellipsis class="max-w-full">
              {{ todo.title }}
            </n-ellipsis>
          </n-button>
          <template #suffix>
            <n-tag size="small">
              {{ t(statusLabels[todo.status]) }}
            </n-tag>
          </template>
        </n-list-item>
      </n-list>
      <n-empty v-else :description="t('没有匹配的待办')" />
      <n-space justify="space-between" class="mt-12">
        <n-pagination v-if="dayTodos.length > 10" v-model:page="dayPage" :item-count="dayTodos.length" :page-size="10" />
        <n-button v-if="day && can('WorkbenchTodoCreate')" type="primary" @click="createForDay">
          {{ t('新建待办') }}
        </n-button>
      </n-space>
    </n-modal>

    <TodoDrawer v-if="selected !== null" :id="selected" :key="selected" ref="drawer" :public-dimensions="publicDimensions" :personal-dimensions="personalDimensions" @close="selected = null" @edit="todo => editor = { todo }" @changed="loadCalendar" />
    <TodoForm v-if="editor" :todo="editor.todo" :date="editor.date" :public-dimensions="publicDimensions" :personal-dimensions="personalDimensions" @close="editor = null" @saved="saved" @conflict="drawer?.refresh()" />
  </CommonPage>
</template>

<script setup lang="ts">
import type { CalendarTodo, Origin, Todo, TodoQuery } from '../api'
import type { DimensionFilter, Period } from '../model'
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { t } from '@/i18n'
import api from '../api'
import { calendarDays, calendarPlacement, formatBusinessToday, originLabels, scopeLabels, statusLabels } from '../model'
import TodoCalendarGrid from '../TodoCalendarGrid.vue'
import TodoCalendarNavigation from '../TodoCalendarNavigation.vue'
import TodoDrawer from '../TodoDrawer.vue'
import TodoForm from '../TodoForm.vue'
import { useDimensionMetadata, useWorkbenchPermission, useWorkbenchRequest } from '../useWorkbench'
import WorkbenchDimensions from '../WorkbenchDimensions.vue'

defineOptions({ name: 'WorkbenchTodoCalendar' })

const can = useWorkbenchPermission()
const router = useRouter()
const { publicRequest, personalRequest, reload } = useDimensionMetadata()
const publicDimensions = publicRequest.data
const personalDimensions = personalRequest.data
const today = formatBusinessToday()
const cursor = ref(today)
const period = ref<Period>('week')
const scope = ref<'mine' | 'created'>('mine')
const origin = ref<Origin | null>(null)
const includeCompleted = ref(false)
const dimensions = ref<DimensionFilter>({})
const calendar = useWorkbenchRequest<CalendarTodo[]>([])
const days = computed(() => calendarDays(cursor.value, period.value))
const selected = ref<number | null>(null)
const editor = ref<{ todo?: Todo, date?: string } | null>(null)
const drawer = ref<InstanceType<typeof TodoDrawer> | null>(null)
const day = ref<string | null>(null)
const dayPage = ref(1)
const scopeOptions = computed(() => ['mine', 'created'].map(value => ({
  value,
  label: t(scopeLabels[value as 'mine' | 'created']),
})))
const originOptions = computed(() => Object.entries(originLabels).map(([value, label]) => ({
  value,
  label: t(label),
})))
const dayTodos = computed(() => day.value
  ? calendar.data.value.filter(todo => calendarPlacement(todo, [day.value!]))
  : [])
const filters = computed<TodoQuery>(() => ({
  scope: scope.value,
  origin: origin.value ?? undefined,
  includeCompleted: includeCompleted.value,
  fromDate: days.value[0],
  toDate: days.value.at(-1),
  ...dimensions.value,
}))

watch(filters, () => {
  calendar.reset()
  day.value = null
  loadCalendar()
}, { immediate: true })

function loadCalendar() {
  return calendar.run(() => api.calendar(filters.value))
}

function openDay(value: string) {
  dayPage.value = 1
  day.value = value
}

function dateLabel(date: string) {
  return can('WorkbenchTodoCreate')
    ? t('在 {0} 新建待办', date)
    : date
}

function pickDate(value: string) {
  if (can('WorkbenchTodoCreate'))
    editor.value = { date: value }
  else
    openDay(value)
}

function openDayTodo(id: number) {
  day.value = null
  selected.value = id
}

function createForDay() {
  if (day.value) {
    editor.value = { date: day.value }
    day.value = null
  }
}

function openUnscheduled() {
  router.push({
    name: 'WorkbenchTodo',
    query: {
      scope: scope.value,
      origin: origin.value ?? undefined,
      unscheduled: '1',
      includeCompleted: String(includeCompleted.value),
      ...dimensions.value,
    },
  })
}

function saved() {
  editor.value = null
  drawer.value?.refresh()
  loadCalendar()
}
</script>
