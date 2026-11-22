<template>
  <section :aria-label="meta.data.value?.title ?? t('待办日历')">
    <n-alert v-if="meta.error.value" type="error">
      {{ meta.error.value }}
      <n-button text @click="loadMeta">
        {{ t('重试') }}
      </n-button>
    </n-alert>
    <n-spin :show="meta.loading.value">
      <template v-if="meta.data.value">
        <h2 class="mb-12 text-20 font-semibold">
          {{ meta.data.value.title }}<template v-if="meta.data.value.context">
            · {{ meta.data.value.context.label }}
          </template>
        </h2>
        <TodoCalendarNavigation
          :cursor="state.date"
          :period="state.view"
          @update:cursor="date => setState({ date })"
          @update:period="view => setState({ view })"
        />
        <n-space align="center" class="mb-12">
          <n-button
            v-for="option in meta.data.value.statuses"
            :key="option.value"
            :type="state.status === option.value ? 'primary' : 'default'"
            :aria-pressed="state.status === option.value"
            @click="setState({ status: option.value })"
          >
            {{ t(option.label) }}<template v-if="calendar.data.value">
              · {{ calendar.data.value.counts[option.value] }}
            </template>
          </n-button>
          <n-button
            :disabled="!calendar.data.value"
            :aria-pressed="mode === 'unscheduled'"
            @click="openList('unscheduled')"
          >
            {{ t('未排期') }}<template v-if="calendar.data.value">
              · {{ calendar.data.value.unscheduledCount }}
            </template>
          </n-button>
        </n-space>
        <n-form inline class="mb-12 flex-wrap" :show-feedback="false">
          <BusinessCalendarDimension
            v-for="dimension in primaryDimensions"
            :key="dimension.key"
            :dimension="dimension"
            :values="state.filters[dimension.key] ?? []"
            :lookup="api.options"
            :selected-options="selectedOptions.data.value[dimension.key] ?? []"
            @update="values => setFilter(dimension.key, values)"
          />
          <n-form-item v-if="secondaryDimensions.length">
            <n-button :aria-expanded="moreFilters" @click="moreFilters = !moreFilters">
              {{ t('更多筛选') }}
            </n-button>
          </n-form-item>
          <template v-if="moreFilters">
            <BusinessCalendarDimension
              v-for="dimension in secondaryDimensions"
              :key="dimension.key"
              :dimension="dimension"
              :values="state.filters[dimension.key] ?? []"
              :lookup="api.options"
              :selected-options="selectedOptions.data.value[dimension.key] ?? []"
              @update="values => setFilter(dimension.key, values)"
            />
          </template>
        </n-form>
        <n-space v-if="chips.length" align="center" class="mb-12">
          <n-tag v-for="chip in chips" :key="JSON.stringify([chip.key, chip.value])" closable @close="removeFilter(chip.key, chip.value)">
            {{ chip.label }}
          </n-tag>
          <n-button text type="primary" @click="clearFilters">
            {{ t('清除筛选') }}
          </n-button>
        </n-space>
        <n-alert v-if="calendar.error.value" type="error" class="mb-12">
          {{ calendar.error.value }}
          <n-button text @click="loadCalendar">
            {{ t('重试') }}
          </n-button>
        </n-alert>
        <n-alert v-if="calendar.data.value?.overflow" type="warning" class="mb-12">
          {{ t('当前日期范围的待办超过日历容量，请缩小日期范围或查看分页列表。') }}
          <n-select
            class="mt-8 max-w-240"
            :value="mode === 'day' ? day : null"
            :options="dateOptions"
            :placeholder="t('查看指定日期')"
            :aria-label="t('查看指定日期')"
            @update:value="openDay"
          />
        </n-alert>
        <template v-if="mode === 'calendar' && !calendar.data.value?.overflow">
          <TodoCalendarGrid
            :cursor="state.date"
            :period="state.view"
            :items="gridItems"
            :loading="calendar.loading.value"
            @select-date="openDay"
            @open-day="openDay"
            @open-item="todo => selected = todo.todoId"
          >
            <template #item="{ todo, dueOnly }">
              {{ dueOnly ? '◆ ' : '' }}{{ todo.title }} · {{ t(statusLabels[todo.status]) }}<template v-if="todo.overdue">
                · {{ t('逾期') }}
              </template><template v-if="todo.recordLabel && !meta.data.value.context">
                · {{ todo.recordLabel }}
              </template>
            </template>
          </TodoCalendarGrid>
          <n-empty v-if="calendar.data.value?.items.length === 0" class="mt-12" :description="t('没有匹配的待办')">
            <template #extra>
              <n-button v-if="chips.length || state.status !== 'all'" text type="primary" @click="clearFilters">
                {{ t('清除筛选') }}
              </n-button>
            </template>
          </n-empty>
        </template>
        <template v-else-if="effectiveMode">
          <n-space align="center" justify="space-between" class="mb-8">
            <strong>{{ listTitle }}<template v-if="list.data.value">
              · {{ list.data.value.total }}
            </template></strong>
            <n-button
              v-if="mode !== 'calendar'"
              text
              type="primary"
              @click="openList('calendar')"
            >
              {{ t(calendar.data.value?.overflow ? '返回日期范围' : '返回日历') }}
            </n-button>
          </n-space>
          <n-spin :show="list.loading.value">
            <n-alert v-if="list.error.value" type="error">
              {{ list.error.value }}
              <n-button text @click="loadList">
                {{ t('重试') }}
              </n-button>
            </n-alert>
            <n-list v-else-if="list.data.value?.items.length">
              <n-list-item v-for="todo in list.data.value.items" :key="todo.todoId">
                <n-button text type="primary" class="max-w-full" @click="selected = todo.todoId">
                  <n-ellipsis class="max-w-full">
                    {{ todo.title }}
                  </n-ellipsis>
                </n-button>
                <div class="mt-4 text-12">
                  {{ todo.recordLabel ? `${todo.recordLabel} · ` : '' }}{{ todo.assigneeName }}<template v-if="effectiveMode.mode === 'range' && (todo.startDate || todo.dueDate)">
                    · {{ todo.startDate ? `${todo.startDate} ~ ${todo.endDate}` : todo.dueDate }}
                  </template>
                </div>
                <template #suffix>
                  <n-space>
                    <n-tag size="small" :type="todo.status === 'DONE' ? 'success' : 'default'">
                      {{ t(statusLabels[todo.status]) }}
                    </n-tag>
                    <n-tag v-if="todo.overdue" type="warning" size="small">
                      {{ t('逾期') }}
                    </n-tag>
                  </n-space>
                </template>
              </n-list-item>
            </n-list>
            <n-empty v-else-if="list.data.value" :description="t('没有匹配的待办')">
              <template #extra>
                <n-button v-if="chips.length || state.status !== 'all'" text type="primary" @click="clearFilters">
                  {{ t('清除筛选') }}
                </n-button>
              </template>
            </n-empty>
          </n-spin>
          <n-pagination v-if="listTotal > 20" v-model:page="page" class="mt-12" :item-count="listTotal" :page-size="20" />
        </template>
      </template>
    </n-spin>
    <BusinessTodoSummary
      v-if="selected"
      :key="selected"
      :load="loadDetail"
      @close="selected = null"
    />
  </section>
</template>

<script setup lang="ts">
import type { LocationQuery } from 'vue-router'
import type { BusinessCalendarMeta, BusinessCalendarResult, BusinessListMode, BusinessOption, BusinessTodo, Page } from './api'
import type { BusinessCalendarList, BusinessCalendarState } from './query'
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { t } from '@/i18n'
import { routeTabKey } from '@/router/routeTabKey'
import { calendarDays, statusLabels } from '../model'
import TodoCalendarGrid from '../TodoCalendarGrid.vue'
import TodoCalendarNavigation from '../TodoCalendarNavigation.vue'
import { useWorkbenchRequest } from '../useWorkbench'
import { businessTodoCalendarApi } from './api'
import BusinessCalendarDimension from './BusinessCalendarDimension.vue'
import BusinessTodoSummary from './BusinessTodoSummary.vue'
import { businessCalendarQuery, readBusinessCalendarListQuery, readBusinessCalendarQuery } from './query'

const props = defineProps<{ sceneCode: string, contextId?: string }>()
const route = useRoute()
const router = useRouter()
const usesHostTab = route.meta.businessCalendarQuery === true
function currentHostKey() {
  return usesHostTab
    ? routeTabKey(router, route)
    : route.path
}
const hostKey = currentHostKey()
const onHostRoute = computed(() => currentHostKey() === hostKey)
const api = computed(() => businessTodoCalendarApi(props.sceneCode, props.contextId))
const meta = useWorkbenchRequest<BusinessCalendarMeta | null>(null)
const calendar = useWorkbenchRequest<BusinessCalendarResult | null>(null)
const list = useWorkbenchRequest<Page<BusinessTodo> | null>(null)
const selectedOptions = useWorkbenchRequest<Record<string, BusinessOption[]>>({})

const moreFilters = ref(false)
const selected = ref<string | null>(null)
const listTotal = ref(0)

const dimensions = computed(() => meta.data.value?.dimensions ?? [])
const primaryDimensions = computed(() => dimensions.value.filter(dimension => dimension.primary))
const secondaryDimensions = computed(() => dimensions.value.filter(dimension => !dimension.primary))
const state = computed(() => readBusinessCalendarQuery(route.query, meta.data.value?.defaultView ?? 'month', dimensions.value))
const queryKey = computed(() => JSON.stringify(state.value))
const filterKey = computed(() => JSON.stringify(state.value.filters))
const days = computed(() => calendarDays(state.value.date, state.value.view))
const listState = computed(() => readBusinessCalendarListQuery(route.query, days.value))
const mode = computed(() => listState.value.mode)
const day = computed(() => listState.value.day)
const page = computed({
  get: () => listState.value.page,
  set: setPage,
})
const range = computed(() => ({ fromDate: days.value[0], toDate: days.value[days.value.length - 1] }))
const dateOptions = computed(() => days.value.map(date => ({ label: date, value: date })))
const gridItems = computed(() => (calendar.data.value?.items ?? []).map(todo => ({ ...todo, id: todo.todoId })))
const effectiveMode = computed<BusinessListMode | null>(() => {
  if (mode.value === 'day')
    return { mode: 'day', date: day.value }
  if (mode.value === 'unscheduled')
    return { mode: 'unscheduled' }
  return calendar.data.value?.overflow
    ? { mode: 'range', ...range.value }
    : null
})
const listTitle = computed(() => {
  if (mode.value === 'day')
    return day.value
  if (mode.value === 'unscheduled')
    return t('未排期')
  return `${range.value.fromDate} ~ ${range.value.toDate}`
})
const chips = computed(() => dimensions.value.flatMap(dimension => (state.value.filters[dimension.key] ?? []).map(value => ({
  key: dimension.key,
  value,
  label: `${dimension.label} · ${dimension.options.find(option => option.value === value)?.label
  ?? selectedOptions.data.value[dimension.key]?.find(option => option.value === value)?.label
  ?? t('所选值')}`,
}))))

function navigate(query: LocationQuery, replace = false) {
  const location = { path: route.path, query, hash: route.hash }
  return replace
    ? router.replace(location)
    : router.push(location)
}

function setState(changes: Partial<BusinessCalendarState>) {
  const next = {
    ...state.value,
    ...changes,
  }
  const query = businessCalendarQuery(route.query, next)
  delete query.calendarPage
  if (mode.value === 'day' && !calendarDays(next.date, next.view).includes(day.value)) {
    delete query.calendarList
    delete query.calendarDay
  }
  return navigate(query)
}

function setPage(value: number) {
  const query = businessCalendarQuery(route.query, state.value)
  if (value > 1)
    query.calendarPage = String(value)
  else
    delete query.calendarPage
  return navigate(query, true)
}

function setFilter(key: string, values: string[]) {
  const filters = { ...state.value.filters }
  if (values.length)
    filters[key] = values
  else
    delete filters[key]
  return setState({ filters })
}

function removeFilter(key: string, value: string) {
  return setFilter(key, state.value.filters[key].filter(current => current !== value))
}

function clearFilters() {
  return setState({ status: 'all', filters: {} })
}

function loadSelectedOptions() {
  selectedOptions.reset()
  if (!meta.data.value || !onHostRoute.value)
    return

  const filters = state.value.filters
  const selected = dimensions.value.filter(dimension => dimension.searchable && filters[dimension.key]?.length)
  const lookup = api.value.options
  return selectedOptions.run(async () => {
    const entries = await Promise.allSettled(selected.map(async ({ key }) => {
      const response = await lookup(key, '', 1, filters[key])
      return [key, response.data.items] as const
    }))
    return {
      data: Object.fromEntries(entries.flatMap(entry => entry.status === 'fulfilled'
        ? [entry.value]
        : [])),
    }
  })
}

function openList(value: BusinessCalendarList, date?: string) {
  const query = businessCalendarQuery(route.query, state.value)
  delete query.calendarPage
  delete query.calendarDay
  if (value === 'calendar') {
    delete query.calendarList
  }
  else {
    query.calendarList = value
    if (value === 'day' && date)
      query.calendarDay = date
  }
  return navigate(query)
}

function openDay(date: string) {
  return openList('day', date)
}

function loadMeta() {
  meta.reset()
  calendar.reset()
  list.reset()
  listTotal.value = 0
  selected.value = null
  selectedOptions.reset()
  return meta.run(api.value.meta)
}

function loadCalendar() {
  if (meta.data.value && onHostRoute.value)
    return calendar.run(() => api.value.calendar(state.value, range.value))
}

async function loadList() {
  list.reset()
  if (meta.data.value && onHostRoute.value && effectiveMode.value) {
    const query = effectiveMode.value
    const requestedPage = page.value
    const result = await list.run(() => api.value.items(state.value, query, requestedPage))
    if (result) {
      listTotal.value = result.total
      const lastPage = Math.max(1, Math.ceil(result.total / 20))
      if (requestedPage > lastPage)
        setPage(lastPage)
    }
  }
}

function loadDetail() {
  return api.value.detail(selected.value!)
}

watch(api, () => {
  moreFilters.value = false
  loadMeta()
}, { immediate: true })
watch([meta.data, queryKey], () => {
  if (!onHostRoute.value)
    return
  calendar.reset()
  list.reset()
  selected.value = null
  listTotal.value = 0
  loadCalendar()
})
watch([
  meta.data,
  queryKey,
  effectiveMode,
  page,
], loadList)
watch([meta.data, filterKey], loadSelectedOptions)

let active = false
onMounted(() => {
  active = true
  document.addEventListener('visibilitychange', onVisibilityChange)
})
onActivated(() => {
  if (!active)
    loadMeta()
  active = true
})
onDeactivated(() => {
  active = false
  calendar.reset()
  list.reset()
  selectedOptions.reset()
  selected.value = null
})
onUnmounted(() => document.removeEventListener('visibilitychange', onVisibilityChange))

function onVisibilityChange() {
  if (active && document.visibilityState === 'visible')
    loadMeta()
}
</script>
