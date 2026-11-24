import type { Assignee, PersonalDimension, PublicDimension } from './api'
import { onMounted, onScopeDispose, ref, shallowRef } from 'vue'
import { t } from '@/i18n'
import { usePermissionStore, useUserStore } from '@/store'
import api from './api'
import { workbenchError } from './model'

export function useWorkbenchPermission(): (code: string) => boolean {
  const user = useUserStore()
  const permissions = usePermissionStore()
  const mock = import.meta.env.DEV && import.meta.env.MODE === 'mock'
  return code => (!mock && user.currentRole.code === 'SUPER_ADMIN') || permissions.resourcesByCode.has(code)
}

export function useWorkbenchRequest<T>(initial: T) {
  const data = shallowRef<T>(initial)
  const loading = ref(false)
  const error = ref('')
  let sequence = 0

  async function run(load: () => Promise<{ data: T }>, fallback = '加载失败，请重试'): Promise<T | undefined> {
    const current = ++sequence
    loading.value = true
    error.value = ''
    try {
      const result = await load()
      if (current === sequence) {
        data.value = result.data
        return result.data
      }
    }
    catch (cause) {
      if (current === sequence)
        error.value = workbenchError(cause, t(fallback))
    }
    finally {
      if (current === sequence)
        loading.value = false
    }
  }

  function reset() {
    sequence++
    loading.value = false
    error.value = ''
    data.value = initial
  }

  onScopeDispose(reset)
  return { data, loading, error, run, reset }
}

export function useDimensionMetadata() {
  const publicRequest = useWorkbenchRequest<PublicDimension[]>([])
  const personalRequest = useWorkbenchRequest<PersonalDimension[]>([])
  const reload = () => Promise.all([
    publicRequest.run(api.publicDimensions),
    personalRequest.run(api.personalDimensions),
  ])
  onMounted(reload)
  return { publicRequest, personalRequest, reload }
}

export function useAssignees(isFlow = () => false, isManager = () => false) {
  const user = useUserStore()
  const assignees = useWorkbenchRequest<Assignee[]>([])
  const can = useWorkbenchPermission()
  const canAssign = () => can('WorkbenchTodoAssign') || (isFlow() && can('WorkbenchTodoFlowManage'))
    || (isManager() && can('WorkbenchTodoManage'))

  async function load() {
    if (canAssign())
      return assignees.run(api.assignees)
  }

  function options(current?: Assignee) {
    const values = [...assignees.data.value]
    if (typeof user.userId === 'number' && !values.some(item => item.id === user.userId))
      values.unshift({ id: user.userId, name: user.displayName })
    if (current && !values.some(item => item.id === current.id))
      values.push(current)
    return values.map(item => ({ label: item.name, value: item.id }))
  }

  return { assignees, canAssign, load, options }
}
