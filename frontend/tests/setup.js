import { computed, h, nextTick, ref } from 'vue'

if (typeof window === 'undefined') {
  Object.assign(globalThis, {
    computed,
    h,
    nextTick,
    ref,
    sessionStorage: {},
  })
}
