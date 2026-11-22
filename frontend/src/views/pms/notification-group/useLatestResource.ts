import { ref, shallowRef, watch } from 'vue'

type Load<T> = (signal: AbortSignal) => Promise<T>

export function useLatestResource<T>(source: () => Load<T> | null) {
  const data = shallowRef<T | null>(null)
  const error = shallowRef<unknown>(null)
  const loading = ref(false)
  const revision = ref(0)

  watch([source, revision], async ([load], _, onCleanup) => {
    data.value = null
    error.value = null
    loading.value = load !== null
    if (!load)
      return

    // 请求取消后仍可能有已排队的响应，因此写入状态前再次检查。
    const controller = new AbortController()
    onCleanup(() => controller.abort())
    try {
      const result = await load(controller.signal)
      if (!controller.signal.aborted)
        data.value = result
    }
    catch (failure) {
      if (!controller.signal.aborted)
        error.value = failure
    }
    finally {
      if (!controller.signal.aborted)
        loading.value = false
    }
  }, { immediate: true })

  return {
    data,
    loading,
    error,
    reload: () => revision.value++,
  }
}
