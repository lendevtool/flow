import type { App, CallRecord, Endpoint, EndpointSource, Grant, SecretVersion } from '../../src/api/open-api/api'
import type { MockDirectory } from './directory'
import { mockTime } from '../fixtures.ts'
import { inRange, MockError, workflowPage } from '../protocol.ts'
import { at, bool, checkVersion, collection, enabled, matchesAny, MockFile, number, route, text, unique } from './common.ts'

const base = '/admin/open-api'
const appPath = `${base}/apps/:id`
const endpointPath = `${base}/endpoints/:id`
const secretPath = `${appPath}/secrets/:id`

export function openApiRoutes({ users }: MockDirectory) {
  const apps = collection<App>([
    {
      id: 1,
      clientId: 'mock-reporting',
      name: '演示报表应用',
      environment: 'DEMO',
      enabled: true,
      ownerUserId: 1,
      ownerDisplayName: users.get(1).nameCn,
      description: '读取演示数据',
      rateLimitCount: 120,
      rateLimitWindowSeconds: 60,
      version: 1,
      createdAt: mockTime(-7),
      updatedAt: mockTime(-7),
    },
  ])
  const sources: EndpointSource[] = [
    {
      sourcePath: '/field-definition/public/metadata',
      httpMethod: 'GET',
      moduleCode: 'FIELD',
      importable: true,
      reason: null,
      parameters: [],
      fields: [
        { name: 'types', type: 'array', nullable: false },
      ],
      collection: false,
      wholeResponse: true,
    },
    {
      sourcePath: '/field-definition/public',
      httpMethod: 'GET',
      moduleCode: 'FIELD',
      importable: true,
      reason: null,
      parameters: [
        { name: 'keyword', type: 'string', required: false },
      ],
      fields: [
        { name: 'code', type: 'string', nullable: false },
      ],
      collection: true,
      page: true,
    },
  ]
  const endpoints = collection<Endpoint>([
    {
      id: 1,
      endpointCode: 'MOCK_FIELD_TYPES',
      moduleCode: 'FIELD',
      displayName: '查询字段类型',
      httpMethod: 'GET',
      pathTemplate: '/open/v1/fields/metadata',
      apiVersion: 'v1',
      status: 'PUBLISHED',
      version: 1,
      documentUrl: '/open-api/docs/v1',
      authorizedAppCount: 1,
      source: { ...sources[0], contractMatches: true },
    },
  ])
  const grants = new Map<string, Grant>([
    [
      '1:1',
      {
        appId: 1,
        endpointId: 1,
        endpointCode: 'MOCK_FIELD_TYPES',
        displayName: '查询字段类型',
        endpointStatus: 'PUBLISHED',
        status: 'GRANTED',
        rateLimitCount: null,
        rateLimitWindowSeconds: null,
        version: 1,
      },
    ],
  ])
  const secrets = new Map<number, SecretVersion[]>()
  const calls: CallRecord[] = [
    {
      id: 1,
      requestId: 'mock-open-1',
      occurredAt: mockTime(-1),
      durationMs: 31,
      httpStatus: 200,
      endpointId: 1,
      endpointCode: 'MOCK_FIELD_TYPES',
      errorCode: null,
      sourceIp: '127.0.0.1',
      result: 'SUCCESS',
    },
  ]
  const expiresAt = () => mockTime(90)
  function versions(appId: number) {
    apps.get(appId)
    const rows = secrets.get(appId)
    if (rows)
      return rows
    const created: SecretVersion[] = []
    secrets.set(appId, created)
    return created
  }
  function secret(params: RegExpMatchArray) {
    const row = versions(Number(params[1])).find(item => item.versionNo === Number(params[2]))
    if (!row)
      throw new MockError(404, '密钥版本不存在')
    return row
  }
  function appValues(body: Record<string, unknown>, current?: App) {
    const ownerUserId = body.ownerUserId === null
      ? null
      : number(body.ownerUserId, current?.ownerUserId ?? 0) || null
    const owner = ownerUserId === null
      ? null
      : users.get(ownerUserId)
    const name = text(body.name, current?.name).trim()
    if (!name)
      throw new MockError(400, '请输入应用名称')
    return {
      name,
      ownerUserId,
      ownerDisplayName: owner?.nameCn || owner?.username || null,
      description: body.description === null
        ? null
        : text(body.description, current?.description ?? '') || null,
      rateLimitCount: number(body.rateLimitCount, current?.rateLimitCount ?? 120),
      rateLimitWindowSeconds: number(body.rateLimitWindowSeconds, current?.rateLimitWindowSeconds ?? 60),
      updatedAt: mockTime(),
      version: (current?.version ?? 0) + 1,
    }
  }
  function registrations(endpointId: number) {
    return Array.from(grants.values()).filter(grant => grant.endpointId === endpointId && grant.status === 'GRANTED')
  }
  function endpointRow(row: Endpoint): Endpoint {
    return { ...row, authorizedAppCount: registrations(row.id).length }
  }
  return [
    route('GET', `${base}/apps`, ({ query }) => workflowPage(apps.rows().filter(row =>
      matchesAny(query.get('keyword'), row.name, row.clientId)
      && enabled(row.enabled, query.get('enabled')) && (!query.has('environment') || row.environment === query.get('environment')),
    ), query)),
    route('GET', at(appPath), ({ params }) => apps.get(params[1])),
    route('POST', `${base}/apps`, ({ body }) => apps.create({
      ...appValues(body),
      clientId: `mock-${crypto.randomUUID().slice(0, 8)}`,
      environment: 'DEMO',
      enabled: true,
      createdAt: mockTime(),
    })),
    route('PATCH', at(appPath), (request) => {
      const app = apps.get(request.params[1])
      checkVersion(request, app.version)
      return Object.assign(app, appValues(request.body, app))
    }),
    route('PUT', at(`${appPath}/status`), (request) => {
      const app = apps.get(request.params[1])
      checkVersion(request, app.version)
      return Object.assign(app, {
        enabled: bool(request.body.enabled, app.enabled),
        version: app.version + 1,
        updatedAt: mockTime(),
      })
    }),
    route('GET', `${base}/owner-options`, ({ query }) => workflowPage(users.rows().filter(user =>
      user.enable && matchesAny(query.get('keyword'), user.nameCn, user.nameEn, user.username),
    ).map(user => ({ id: user.id, displayName: user.nameCn || user.username })), query)),
    route('GET', `${base}/endpoint-sources`, ({ query }) => sources.filter(row => enabled(row.importable, query.get('importable')))),
    route('GET', `${base}/endpoints`, ({ query }) => workflowPage(endpoints.rows().filter(row =>
      matchesAny(query.get('keyword'), row.displayName, row.endpointCode, row.pathTemplate)
      && (!query.has('moduleCode') || row.moduleCode === query.get('moduleCode'))
      && (!query.has('status') || row.status === query.get('status')),
    ).map(endpointRow), query)),
    route('GET', at(endpointPath), ({ params }) => endpointRow(endpoints.get(params[1]))),
    route('POST', `${base}/endpoints/import`, ({ body }) => {
      const source = sources.find(row => row.sourcePath === body.sourcePath && row.importable)
      if (!source)
        throw new MockError(400, '请选择可导入的接口来源')
      const endpointCode = text(body.endpointCode)
      const pathTemplate = text(body.pathTemplate)
      unique(endpoints.rows(), endpointCode, row => row.endpointCode)
      unique(endpoints.rows(), pathTemplate, row => row.pathTemplate)
      return endpoints.create({
        endpointCode,
        moduleCode: source.moduleCode,
        displayName: text(body.displayName),
        httpMethod: source.httpMethod,
        pathTemplate,
        apiVersion: 'v1',
        status: 'REGISTERED',
        version: 1,
        documentUrl: '/open-api/docs/v1',
        authorizedAppCount: 0,
        source: { ...source, contractMatches: true },
      })
    }),
    route('GET', at(`${endpointPath}/apps`), ({ params, query }) =>
      workflowPage(registrations(endpoints.get(params[1]).id).map(grant => apps.get(grant.appId)), query)),
    route('PUT', at(`${endpointPath}/status`), (request) => {
      const endpoint = endpoints.get(request.params[1])
      checkVersion(request, endpoint.version)
      const status = request.body.status
      if (status !== 'PUBLISHED' && status !== 'OFFLINE')
        throw new MockError(400, '接口状态无效')
      Object.assign(endpoint, { status, version: endpoint.version + 1 })
      return endpointRow(endpoint)
    }),
    route('PUT', at(`${endpointPath}/source-contract`), (request) => {
      const endpoint = endpoints.get(request.params[1])
      checkVersion(request, endpoint.version)
      if (endpoint.source)
        endpoint.source.contractMatches = true
      endpoint.version++
      return endpointRow(endpoint)
    }),
    route('GET', `${base}/docs/v1`, () => new MockFile(JSON.stringify({
      openapi: '3.1.0',
      info: { title: 'Flow Open API · Mock', version: '1.0.0' },
      paths: Object.fromEntries(endpoints.rows().filter(row => row.status === 'PUBLISHED').map(row =>
        [
          row.pathTemplate,
          {
            get: {
              summary: row.displayName,
              operationId: row.endpointCode,
              responses: {
                200: { description: 'Mock response' },
              },
            },
          },
        ],
      )),
    }, null, 2), 'application/json; charset=utf-8', 'flow-open-api-v1.json')),
    route('GET', at(`${appPath}/grants`), ({ params }) => {
      const app = apps.get(params[1])
      return Array.from(grants.values()).filter(row => row.appId === app.id).map(row => ({
        ...row,
        endpointStatus: endpoints.get(row.endpointId).status,
      }))
    }),
    route('PUT', at(`${appPath}/grants/:id`), (request) => {
      const { body, params } = request
      const app = apps.get(params[1])
      const endpoint = endpoints.get(params[2])
      const key = `${app.id}:${endpoint.id}`
      const current = grants.get(key)
      if (current)
        checkVersion(request, current.version)
      if (body.status !== 'GRANTED' && (body.status !== 'REVOKED' || !current))
        throw new MockError(400, '授权状态无效')
      const row: Grant = {
        appId: app.id,
        endpointId: endpoint.id,
        endpointCode: endpoint.endpointCode,
        displayName: endpoint.displayName,
        endpointStatus: endpoint.status,
        status: body.status,
        rateLimitCount: number(body.rateLimitCount, 0) || null,
        rateLimitWindowSeconds: number(body.rateLimitWindowSeconds, 0) || null,
        version: (current?.version ?? 0) + 1,
      }
      grants.set(key, row)
      return row
    }),
    route('GET', at(`${appPath}/secrets`), ({ params }) => versions(Number(params[1])).slice().reverse()),
    route('POST', at(`${appPath}/secrets`), ({ params }) => {
      const rows = versions(Number(params[1]))
      if (rows.some(row => row.status === 'CANDIDATE'))
        throw new MockError(409, '已有待激活的演示密钥')
      const row: SecretVersion = {
        versionNo: rows.length + 1,
        status: 'CANDIDATE',
        expiresAt: expiresAt(),
        createdAt: mockTime(),
        activatedAt: null,
        revokedAt: null,
        tokensRevokedAt: null,
      }
      rows.push(row)
      return { versionNo: row.versionNo, clientSecret: `mock-only-${crypto.randomUUID()}`, expiresAt: row.expiresAt }
    }),
    route('POST', at(`${secretPath}/activate`), ({ params }) => {
      const row = secret(params)
      if (row.status !== 'CANDIDATE')
        throw new MockError(409, '仅待激活密钥可以启用')
      const replaced = versions(Number(params[1])).find(item => item.status === 'ACTIVE') ?? null
      if (replaced)
        replaced.status = 'RETIRING'
      Object.assign(row, {
        status: 'ACTIVE',
        activatedAt: mockTime(),
      })
      return { activated: row, replaced }
    }),
    route('POST', at(`${secretPath}/revoke`), ({ params, body }) => {
      const row = secret(params)
      return Object.assign(row, {
        status: 'REVOKED',
        revokedAt: mockTime(),
        tokensRevokedAt: body.mode === 'COMPROMISED'
          ? mockTime()
          : null,
      })
    }),
    route('GET', at(`${appPath}/calls`), ({ params, query }) => {
      const app = apps.get(params[1])
      const rows = app.id === 1
        ? calls
        : []
      return workflowPage(rows.filter(row =>
        (!query.has('endpointId') || row.endpointId === Number(query.get('endpointId')))
        && (!query.has('result') || row.result === query.get('result'))
        && matchesAny(query.get('requestId'), row.requestId)
        && inRange(row.occurredAt.slice(0, 10), query.get('from')?.slice(0, 10) ?? null, query.get('to')?.slice(0, 10) ?? null),
      ), query)
    }),
  ]
}
