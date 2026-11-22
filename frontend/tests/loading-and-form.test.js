import { afterEach, describe, expect, it, vi } from 'vitest'
import { useCrud } from '../src/composables/useCrud'
import { createPageLoadingGuard } from '../src/router/guards/page-loading-guard'

function setupGuard() {
  const hooks = {}
  const bar = {
    start: vi.fn(),
    finish: vi.fn(),
    error: vi.fn(),
  }
  vi.stubGlobal('$loadingBar', bar)
  createPageLoadingGuard({
    beforeEach: handler => hooks.before = handler,
    afterEach: handler => hooks.after = handler,
    onError: handler => hooks.error = handler,
  })
  return { hooks, bar }
}

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('page loading', () => {
  it('does not finish a new navigation using the previous completion timer', () => {
    vi.useFakeTimers()
    const { hooks, bar } = setupGuard()
    hooks.before()
    hooks.after()
    vi.advanceTimersByTime(100)
    hooks.before()
    vi.advanceTimersByTime(200)
    expect(bar.finish).not.toHaveBeenCalled()
    hooks.after()
    vi.advanceTimersByTime(200)
    expect(bar.finish).toHaveBeenCalledOnce()
  })

  it('does not overwrite an error with a delayed finish', () => {
    vi.useFakeTimers()
    const { hooks, bar } = setupGuard()
    hooks.before()
    hooks.after()
    hooks.error()
    vi.runAllTimers()
    expect(bar.error).toHaveBeenCalledOnce()
    expect(bar.finish).not.toHaveBeenCalled()
  })
})

it('keeps nested row values unchanged until an edit is saved', () => {
  const row = {
    id: 1,
    profile: { name: 'before' },
    tags: ['original'],
  }
  const crud = useCrud({ name: '记录' })
  crud.handleEdit(row)
  crud.modalForm.value.profile.name = 'after'
  crud.modalForm.value.tags.push('new')
  expect(row).toEqual({
    id: 1,
    profile: { name: 'before' },
    tags: ['original'],
  })
})
