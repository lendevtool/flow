import { describe, expect, it } from 'vitest'
import { actorLabel, cellText, queryParams, resultInfo } from '../src/views/pms/audit/format'

describe('audit presentation', () => {
  it('distinguishes absent, null, empty, false, zero and hidden values', () => {
    expect(cellText({ present: false })).toBe('不存在')
    expect(cellText({ present: true, value: null })).toBe('未设置')
    expect(cellText({ present: true, value: '' })).toBe('（空字符串）')
    expect(cellText({ present: true, value: 0 })).toBe('0')
    expect(cellText({ present: true, value: false })).toBe('否')
    expect(cellText({ present: true, redacted: true, value: 'must-not-render' })).toBe('内容不展示')
    expect(cellText({ present: true, value: 'OPEN', display: '开启' })).toBe('开启')
  })
  it('keeps anonymous claimed accounts distinct from verified actors', () => {
    expect(actorLabel({ actorType: 'ANONYMOUS', claimedAccount: 'admin' })).toContain('未认证')
    expect(actorLabel({ actorType: 'USER', actorName: '张三', actorAccount: 'zhang' })).toBe('张三（zhang）')
  })
  it('preserves real criteria and turns local picker milliseconds into UTC without empty filters', () => {
    const filters = {
      moduleCode: 'PARAM',
      actorKeyword: '',
      result: null,
      range: [0, 1000],
    }
    expect(queryParams(filters, 2, 20)).toEqual({
      moduleCode: 'PARAM',
      pageNo: 2,
      pageSize: 20,
      startTime: '1970-01-01T00:00:00.000Z',
      endTime: '1970-01-01T00:00:01.000Z',
    })
    expect(filters.range).toEqual([0, 1000])
    expect(resultInfo('UNKNOWN').label).toBe('结果未确认')
  })
})
