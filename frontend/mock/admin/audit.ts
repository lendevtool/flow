import { mockTime } from '../fixtures.ts'
import { workflowPage } from '../protocol.ts'
import { demoRole, demoUser } from '../session.ts'
import { at, checkVersion, collection, matchesAny, MockFile, route } from './common.ts'

export function auditRoutes() {
  const log = {
    id: 1,
    operationId: '00000000-0000-4000-8000-000000000001',
    requestId: 'mock-audit-1',
    actorType: 'USER',
    actorId: String(demoUser.id),
    actorAccount: demoUser.username,
    actorName: demoUser.name,
    actorRoleName: demoRole.name,
    actorRoleCode: demoRole.code,
    moduleCode: 'SYSTEM',
    operationCode: 'param.group.update',
    summary: '修改参数组',
    result: 'SUCCESS',
    occurredAt: mockTime(-1),
    durationMs: 28,
    requestMethod: 'PATCH',
    requestPath: '/param/groups/1',
    sourceIp: '127.0.0.1',
    errorCode: null,
    errorMessage: null,
    objectCount: 1,
    changedObjectCount: 1,
    fieldChangeCount: 1,
  }
  const entry = {
    objectNo: 1,
    objectType: 'PARAM_GROUP',
    objectId: '1',
    objectCode: 'DEMO_PRIORITY',
    objectName: '演示优先级',
    parentType: null,
    parentId: null,
    entryType: 'CHANGE',
    fieldChangeCount: 1,
    beforeData: { name: '旧参数名' },
    afterData: { name: '演示优先级' },
    changes: [
      {
        path: 'name',
        label: '名称',
        changeType: 'UPDATE',
        before: {
          present: true,
          value: '旧参数名',
          redacted: false,
          display: null,
        },
        after: {
          present: true,
          value: '演示优先级',
          redacted: false,
          display: null,
        },
      },
    ],
  }
  const logs = collection([log])
  const outbox = collection([
    {
      id: 1,
      rowVersion: 1,
      status: 'RETRY',
      createdAt: log.occurredAt,
      operationCode: log.operationCode,
      attempts: 1,
      failureCount: 1,
      nextRetryAt: mockTime(),
      prepareDeadlineAt: null,
      lockedUntil: null,
      lastErrorMessage: '演示投递延迟',
    },
  ])
  function matchesLog(query: URLSearchParams) {
    const start = query.get('startTime')
    const end = query.get('endTime')
    return logs.rows().filter(row =>
      matchesAny(query.get('actorKeyword'), row.actorName, row.actorAccount)
      && (!start || Date.parse(row.occurredAt) >= Date.parse(start))
      && (!end || Date.parse(row.occurredAt) <= Date.parse(end))
      && Object.entries({
        ...entry,
        ...row,
      }).every(([key, value]) => !query.has(key) || String(value) === query.get(key)),
    )
  }
  return [
    route('GET', '/audit/options', () => ({
      modules: [
        { value: log.moduleCode, label: '系统管理' },
      ],
      operations: [
        { value: log.operationCode, module: log.moduleCode, label: '修改参数组' },
      ],
    })),
    route('GET', '/audit/status', () => ({
      counts: Object.fromEntries(['RETRY', 'DEAD', 'DONE'].map(status => [status, outbox.rows().filter(row => row.status === status).length])),
      oldestPendingAt: outbox.rows().find(row => row.status === 'RETRY')?.createdAt ?? null,
    })),
    route('GET', '/audit/logs', ({ query }) => workflowPage(matchesLog(query), query)),
    route('GET', at('/audit/logs/:id'), ({ params, query }) => ({
      log: logs.get(params[1]),
      objects: workflowPage([entry], query),
    })),
    route('GET', '/audit/logs/export', ({ query }) => new MockFile(
      [
        'id,operationCode,result,occurredAt',
        ...matchesLog(query).map(row => `${row.id},${row.operationCode},${row.result},${row.occurredAt}`),
      ].join('\n'),
      'text/csv; charset=utf-8',
      'operation-audit.csv',
    )),
    route('GET', '/audit/outbox', ({ query }) => workflowPage(outbox.rows().filter(row => !query.has('status') || row.status === query.get('status')), query)),
    route('POST', at('/audit/outbox/:id/retry'), (request) => {
      const row = outbox.get(request.params[1])
      checkVersion(request, row.rowVersion, 'rowVersion')
      row.status = 'DONE'
      row.rowVersion++
      row.attempts++
      row.lastErrorMessage = ''
    }),
  ]
}
