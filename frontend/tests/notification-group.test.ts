import { describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, ref } from 'vue'
import { setLanguage } from '@/i18n'
import { auditObjectFilters, operationSummary } from '@/views/pms/audit/format'
import { countTrimmedCharacters, groupWrite, selectPage, workflowSceneWrite } from '@/views/pms/notification-group/model'
import { useLatestResource } from '@/views/pms/notification-group/useLatestResource'

describe('notification group configuration', () => {
  it('preserves selections from previous result pages when the current page is edited', () => {
    const first = { id: 1, name: 'First' }
    const second = { id: 2, name: 'Second' }
    const third = { id: 3, name: 'Third' }
    const selected = selectPage(new Map(), [first, second], [1, 2], 3)
    const nextPage = selectPage(selected, [third], [3], 3)

    expect([
      ...nextPage.values(),
    ]).toEqual([first, second, third])
    expect([
      ...selectPage(nextPage, [first, second], [2], 3).values(),
    ]).toEqual([second, third])
  })

  it('rejects a selection beyond the remaining draft capacity', () => {
    const first = { id: 1, name: 'First' }
    const second = { id: 2, name: 'Second' }
    const selected = selectPage(new Map(), [first], [1], 1)

    expect(selectPage(selected, [second], [2], 1)).toBe(selected)
  })

  it('retains historical references, keeps each department scope and sends only write fields', () => {
    expect(groupWrite(' Finance ', '  ', {
      users: [
        {
          userId: 43,
          nameCn: null,
          nameEn: null,
          username: null,
          status: 'MISSING',
        },
      ],
      departments: [
        {
          departmentId: 2,
          path: 'Finance',
          includeDescendants: true,
          status: 'DISABLED',
        },
        {
          departmentId: 3,
          path: 'IT',
          includeDescendants: false,
          status: 'ACTIVE',
        },
      ],
    })).toEqual({
      name: 'Finance',
      description: null,
      userIds: [43],
      departments: [
        { departmentId: 2, includeDescendants: true },
        { departmentId: 3, includeDescendants: false },
      ],
    })
  })
})

it('keeps the current result when an older search finishes late', async () => {
  const term = ref('first')
  const old = Promise.withResolvers<string>()
  const scope = effectScope()
  let previousSignal: AbortSignal | undefined
  const state = scope.run(() => useLatestResource(() => {
    const keyword = term.value
    return (signal) => {
      if (keyword === 'first') {
        previousSignal = signal
        return old.promise
      }
      return Promise.resolve('matching latest search')
    }
  }))!
  try {
    term.value = 'second'
    await nextTick()
    expect(previousSignal?.aborted).toBe(true)
    await vi.waitFor(() => expect(state.data.value).toBe('matching latest search'))
    old.resolve('stale response')
    await nextTick()
    expect(state.data.value).toBe('matching latest search')
    expect(state.loading.value).toBe(false)
  }
  finally {
    old.resolve('')
    scope.stop()
  }
})

it('prefills exact audit criteria from scalar URL values', () => {
  const query = { moduleCode: 'NOTIFICATION_GROUP', objectType: 'NOTIFICATION_SCENE', objectId: 'month.end' }
  expect(auditObjectFilters(query)).toEqual(query)
  expect(auditObjectFilters({
    ...query,
    objectId: ['first', 'second'],
  })).toBeNull()
  expect(auditObjectFilters({ ...query, objectId: ' ' })).toBeNull()
})

it('counts Unicode code points after trimming group text', () => {
  expect(countTrimmedCharacters('  📨群组  ')).toBe(3)
})

it('translates the audit operation while preserving the recorded group name', () => {
  setLanguage('en-US')
  try {
    expect(operationSummary({
      operationCode: 'system.notification.group.update',
      summary: '修改通知群组：月结提醒',
    })).toBe('Update notification group · 月结提醒')
  }
  finally {
    setLanguage('zh-CN')
  }
})

it('writes cleared optional scene settings as null and includes the current version', () => {
  expect(workflowSceneWrite({
    sceneCode: ' workflow.review ',
    nameCn: ' 审核 ',
    nameEn: ' ',
    titleTemplate: ' {{node.name}} ',
    summaryTemplate: ' ',
    enabled: false,
    mailTemplateId: null,
  }, 9)).toEqual({
    sceneCode: 'workflow.review',
    nameCn: '审核',
    nameEn: null,
    titleTemplate: '{{node.name}}',
    summaryTemplate: null,
    enabled: false,
    mailTemplateId: null,
    expectedVersion: 9,
  })
})
