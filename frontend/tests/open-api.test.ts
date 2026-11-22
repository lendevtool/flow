import { describe, expect, it } from 'vitest'
import { appPatch, callTimeRange, endpointImportPayload, endpointModuleLabel, grantPayload, openApiPermissions, secretActions } from '../src/views/pms/open-api/model'

describe('open API permission buttons', () => {
  it('checks each permission against the current role and route buttons', () => {
    expect(openApiPermissions('OPERATOR', [
      { code: 'OpenApiGrantManage' },
    ])).toEqual({
      apps: false,
      endpoints: false,
      grants: true,
      secrets: false,
      calls: false,
    })
    expect(openApiPermissions('SUPER_ADMIN', undefined)).toEqual({
      apps: true,
      endpoints: true,
      grants: true,
      secrets: true,
      calls: true,
    })
  })
})

it('only writes changed app fields and can explicitly remove a previous owner', () => {
  const initial = {
    name: 'Calendar',
    ownerUserId: 42,
    description: null,
    rateLimitCount: 600,
    rateLimitWindowSeconds: 60,
    version: 3,
  }
  expect(appPatch(initial, { ...initial, name: 'Trading calendar' })).toEqual({
    name: 'Trading calendar',
    version: 3,
  })
  expect(appPatch(initial, { ...initial, ownerUserId: null })).toEqual({
    ownerUserId: null,
    version: 3,
  })
})

describe('open API grant form', () => {
  it('grants an endpoint without a resource scope or limit override', () => {
    expect(grantPayload({
      limitCount: null,
      limitWindow: null,
    }, null)).toEqual({
      status: 'GRANTED',
      rateLimitCount: null,
      rateLimitWindowSeconds: null,
      version: null,
    })
  })

  it('writes the quota and version for an existing grant without carrying its old range', () => {
    const draft = {
      mode: 'RESTRICTED',
      scopeIds: [11],
      limitCount: 5,
      limitWindow: 30,
    }
    expect(grantPayload(draft, 7)).toEqual({
      status: 'GRANTED',
      rateLimitCount: 5,
      rateLimitWindowSeconds: 30,
      version: 7,
    })
  })
})

describe('open API endpoint import form', () => {
  const source = {
    sourcePath: '/trading-calendar/status',
    fields: [
      { name: 'calendarDate' },
      { name: 'isTradingDay' },
    ],
  }
  const draft = {
    displayName: ' Calendar status ',
    endpointCode: ' calendar.status.read ',
    slug: ' calendar-status ',
    responseFields: ['isTradingDay', 'calendarDate'],
  }

  it('labels the new management modules in the source selector', () => {
    const modules = ['param', 'user', 'role', 'permission']
    expect(modules.map(endpointModuleLabel)).toEqual([
      '参数管理',
      '用户管理',
      '角色管理',
      '资源管理',
    ])
  })

  it('builds the managed path and preserves the source field order', () => {
    expect(endpointImportPayload(source, draft)).toEqual({
      sourcePath: '/trading-calendar/status',
      displayName: 'Calendar status',
      endpointCode: 'calendar.status.read',
      pathTemplate: '/open/v1/managed/calendar-status',
      responseFields: ['calendarDate', 'isTradingDay'],
    })
  })

  it('rejects invalid route suffixes and fields outside the selected source', () => {
    expect(endpointImportPayload(source, { ...draft, slug: 'calendar/status' })).toBeNull()
    expect(endpointImportPayload(source, { ...draft, responseFields: [] })).toBeNull()
    expect(endpointImportPayload(source, {
      ...draft,
      responseFields: ['nameEn'],
    })).toBeNull()
  })

  it('imports a whole-response source without submitting field selections', () => {
    const wholeSource = { sourcePath: '/department', fields: [], wholeResponse: true }
    expect(endpointImportPayload(wholeSource, {
      displayName: '部门树',
      endpointCode: 'department.tree',
      slug: 'departments',
      responseFields: [],
    })).toEqual({
      sourcePath: '/department',
      displayName: '部门树',
      endpointCode: 'department.tree',
      pathTemplate: '/open/v1/managed/departments',
    })
  })
})

describe('open API secret operations', () => {
  const now = Date.parse('2026-11-20T07:00:00Z')
  const current = '2026-11-20T08:00:00Z'
  const expired = '2026-11-20T07:00:00Z'

  it('removes the activate action for expired candidates', () => {
    expect(secretActions('CANDIDATE', current, null, now)).toEqual(['activate', 'ABANDON', 'COMPROMISED'])
    expect(secretActions('CANDIDATE', expired, null, now)).toEqual(['ABANDON', 'COMPROMISED'])
  })

  it('lets a normally retired version be revoked in an emergency', () => {
    expect(secretActions('REVOKED', expired, null, now)).toEqual(['COMPROMISED'])
    expect(secretActions('REVOKED', expired, current, now)).toEqual([])
  })
})

describe('open API call dates', () => {
  it('includes the full selected business day using UTC request boundaries', () => {
    expect(callTimeRange(['2026-11-20', '2026-11-20'])).toEqual({
      from: '2026-11-19T16:00:00.000Z',
      to: '2026-11-20T16:00:00.000Z',
    })
    expect(callTimeRange(null)).toEqual({})
  })

  it('allows 31 business days and rejects larger or reversed ranges', () => {
    expect(callTimeRange(['2026-10-01', '2026-10-31'])).not.toBeNull()
    expect(callTimeRange(['2026-10-01', '2026-11-01'])).toBeNull()
    expect(callTimeRange(['2026-11-20', '2026-11-19'])).toBeNull()
  })
})
