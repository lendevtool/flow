import type { DeliveryRow, DepartmentSelection, GroupDetail, SceneDetail, SceneRow, WorkflowSceneUsages } from '../../src/views/pms/notification-group/api'
import type { MockDirectory, MockUser } from './directory'
import type { MockMail } from './mail'
import { mockTime } from '../fixtures.ts'
import { inRange, MockError, object, workflowPage } from '../protocol.ts'
import { at, bool, checkVersion, collection, enabled, ids, matchesAny, number, route, text } from './common.ts'

interface GroupRecord {
  id: number
  name: string
  description: string | null
  enabled: boolean
  version: number
  userIds: number[]
  departments: DepartmentSelection[]
  updatedAt: string
}

interface SceneRecord extends Omit<SceneDetail, 'groups'> {
  groupIds: number[]
}

export function createNotifications({ users, departments }: MockDirectory, { templates }: MockMail) {
  const base = '/notification-groups'
  const groups = collection<GroupRecord>([
    {
      id: 1,
      name: '流程运营群组',
      description: '流程审批事件接收人',
      enabled: true,
      version: 1,
      userIds: [1],
      departments: [
        { departmentId: 3, includeDescendants: false },
      ],
      updatedAt: mockTime(-1),
    },
  ])
  const initialScene: SceneRecord = {
    sceneCode: 'DEMO_WORKFLOW_APPROVED',
    sceneType: 'WORKFLOW_EXTRA',
    enabled: true,
    version: 1,
    nameCn: '流程审批通过',
    nameEn: 'Workflow Approved',
    titleTemplate: '{{flow.title}} 已通过',
    summaryTemplate: null,
    mailTemplateId: 1,
    groupIds: [1],
  }
  const scenes = new Map([
    [initialScene.sceneCode, initialScene],
  ])
  const deliveries = collection<DeliveryRow>([
    {
      id: 1,
      flowInstanceId: 1,
      nodeInstanceId: 1,
      eventId: 1,
      taskId: null,
      sceneCode: initialScene.sceneCode,
      kind: 'NODE_APPROVED',
      status: 'DONE',
      attemptCount: 1,
      sceneVersion: 1,
      createdAt: mockTime(-1),
      preparedAt: mockTime(-1),
      finishedAt: mockTime(-1),
      lastErrorCode: null,
    },
  ])

  function select(body: Record<string, unknown>) {
    const userIds = ids(body.userIds)
    userIds.forEach(id => users.get(id))
    const selected = new Map<number, DepartmentSelection>()
    if (Array.isArray(body.departments)) {
      for (const value of body.departments) {
        const row = object(value)
        const departmentId = number(row.departmentId, 0)
        departments.get(departmentId)
        selected.set(departmentId, { departmentId, includeDescendants: row.includeDescendants === true })
      }
    }
    return {
      userIds,
      departments: Array.from(selected.values()),
    }
  }
  function groupInput(body: Record<string, unknown>, current?: GroupRecord) {
    const name = text(body.name, current?.name).trim()
    if (!name)
      throw new MockError(400, '请输入群组名称')
    return {
      name,
      description: text(body.description) || null,
      enabled: current?.enabled ?? true,
      version: (current?.version ?? 0) + 1,
      updatedAt: mockTime(),
      ...select(body),
    }
  }
  function recipient(user: MockUser) {
    return {
      userId: user.id,
      username: user.username,
      nameCn: user.nameCn,
      nameEn: user.nameEn,
    }
  }
  function departmentWithin(id: number, root: DepartmentSelection) {
    for (let next: number | null = id; next !== null; next = departments.get(next).parentId) {
      if (next === root.departmentId)
        return true
      if (!root.includeDescendants)
        return false
    }
    return false
  }
  function departmentPath(id: number) {
    const path = []
    for (let next: number | null = id; next !== null;) {
      const row = departments.get(next)
      path.unshift(row.name)
      next = row.parentId
    }
    return path.join(' / ')
  }
  function groupDetail(group: GroupRecord): GroupDetail {
    const { userIds, departments: selections, ...current } = group
    return {
      ...current,
      users: userIds.map((id) => {
        const user = users.has(id)
          ? users.get(id)
          : null
        if (!user) {
          return {
            userId: id,
            username: null,
            nameCn: null,
            nameEn: null,
            status: 'MISSING',
          }
        }
        return {
          ...recipient(user),
          status: user.enable ? 'ACTIVE' : 'DISABLED',
        }
      }),
      departments: selections.map(row => ({
        ...row,
        path: departmentPath(row.departmentId),
        status: departments.get(row.departmentId).enabled
          ? 'ACTIVE'
          : 'DISABLED',
      })),
    }
  }
  function recipients(sources: Pick<GroupRecord, 'userIds' | 'departments'>) {
    return users.rows().filter(user =>
      user.enable && (sources.userIds.includes(user.id) || sources.departments.some(root =>
        departments.get(root.departmentId).enabled && user.departmentIds.some(id => departmentWithin(id, root)),
      )),
    ).map(recipient)
  }
  function usages(id: number) {
    return Array.from(scenes.values()).filter(scene => scene.groupIds.includes(id)).map(({ sceneCode, nameCn, nameEn }) => ({ sceneCode, nameCn, nameEn }))
  }
  function scene(code: string) {
    const row = scenes.get(code)
    if (!row)
      throw new MockError(404, '通知场景不存在')
    return row
  }
  function sceneDetail(row: SceneRecord): SceneDetail {
    const { groupIds, ...settings } = row
    return {
      ...settings,
      groups: groupIds.map((id) => {
        const { name } = groups.get(id)
        return { id, name }
      }),
    }
  }
  function sceneWrite(body: Record<string, unknown>, previous?: SceneRecord) {
    const sceneCode = previous?.sceneCode ?? text(body.sceneCode).trim()
    const nameCn = text(body.nameCn).trim()
    if (!sceneCode || !nameCn || (!previous && scenes.has(sceneCode)))
      throw new MockError(400, '通知场景编码或名称无效')
    const mailTemplateId = number(body.mailTemplateId, 0) || null
    if (mailTemplateId)
      templates.get(mailTemplateId)
    const record: SceneRecord = {
      sceneCode,
      nameCn,
      nameEn: text(body.nameEn) || null,
      sceneType: previous?.sceneType ?? 'WORKFLOW_EXTRA',
      groupIds: previous?.groupIds ?? [],
      enabled: bool(body.enabled, true),
      titleTemplate: text(body.titleTemplate) || null,
      summaryTemplate: text(body.summaryTemplate) || null,
      mailTemplateId,
      version: (previous?.version ?? 0) + 1,
    }
    scenes.set(sceneCode, record)
    return sceneDetail(record)
  }

  const routes = (sceneUsage: (code: string) => WorkflowSceneUsages) => [
    route('GET', base, ({ query }) => workflowPage(groups.rows().filter(row =>
      enabled(row.enabled, query.get('enabled')) && matchesAny(query.get('keyword'), row.name, row.description),
    ).map(({ userIds, departments, ...row }) => ({
      ...row,
      userCount: userIds.length,
      departmentCount: departments.length,
      usageCount: usages(row.id).length,
    })), query)),
    route('GET', at(`${base}/:id`), ({ params }) => groupDetail(groups.get(params[1]))),
    route('POST', base, ({ body }) => groupDetail(groups.create(groupInput(body)))),
    route('PUT', at(`${base}/:id`), (request) => {
      const row = groups.get(request.params[1])
      checkVersion(request, row.version, 'expectedVersion')
      return groupDetail(Object.assign(row, groupInput(request.body, row)))
    }),
    route('PUT', at(`${base}/:id/status`), (request) => {
      const row = groups.get(request.params[1])
      checkVersion(request, row.version, 'expectedVersion')
      Object.assign(row, {
        enabled: bool(request.body.enabled, row.enabled),
        version: row.version + 1,
        updatedAt: mockTime(),
      })
      return groupDetail(row)
    }),
    route('GET', at(`${base}/:id/recipients`), ({ params, query }) => workflowPage(recipients(groups.get(params[1])), query)),
    route('POST', `${base}/preview`, ({ body }) => workflowPage(recipients(select(body)), new URLSearchParams({
      pageNo: String(number(body.pageNo, 1)),
      pageSize: String(number(body.pageSize, 20)),
    }))),
    route('GET', at(`${base}/:id/usages`), ({ params, query }) => workflowPage(usages(groups.get(params[1]).id), query)),
    route('GET', `${base}/candidates/users`, ({ query }) => workflowPage(users.rows().filter(row =>
      row.enable && matchesAny(query.get('keyword'), row.username, row.nameCn, row.nameEn),
    ).map(recipient), query)),
    route('GET', `${base}/candidates/departments`, () => departments.rows().filter(row => row.enabled).map(({ id, parentId, name, sortOrder }) => ({
      departmentId: id,
      parentId,
      name,
      sortOrder,
    }))),
    route('GET', `${base}/options`, ({ query }) => workflowPage(groups.rows().filter(row => row.enabled && matchesAny(query.get('keyword'), row.name)).map(({ id, name }) => ({ id, name })), query)),
    route('GET', `${base}/scenes`, ({ query }) => {
      const rows: SceneRow[] = Array.from(scenes.values()).map(({ groupIds, ...row }) => ({ ...row, groupCount: groupIds.length }))
      return workflowPage(rows, query)
    }),
    route('GET', at(`${base}/scenes/:code`), ({ params }) => sceneDetail(scene(params[1]))),
    route('PUT', at(`${base}/scenes/:code/groups`), (request) => {
      const row = scene(request.params[1])
      checkVersion(request, row.version, 'expectedVersion')
      const groupIds = ids(request.body.groupIds)
      groupIds.forEach(id => groups.get(id))
      Object.assign(row, { groupIds, version: row.version + 1 })
      return sceneDetail(row)
    }),
    route('POST', `${base}/scenes/workflow`, ({ body }) => sceneWrite(body)),
    route('PUT', at(`${base}/scenes/workflow/:code`), (request) => {
      const row = scene(request.params[1])
      checkVersion(request, row.version, 'expectedVersion')
      return sceneWrite(request.body, row)
    }),
    route('GET', at(`${base}/scenes/workflow/:code/usages`), ({ params }) => {
      scene(params[1])
      return sceneUsage(params[1])
    }),
    route('GET', `${base}/scenes/workflow/mail-templates`, () => templates.rows().map(({ id, code, name, enable }) => ({ id, code, name, enable }))),
    route('GET', `${base}/workflow-deliveries`, ({ query }) => workflowPage(deliveries.rows().filter(row =>
      (!query.has('sceneCode') || row.sceneCode === query.get('sceneCode'))
      && (!query.has('status') || row.status === query.get('status'))
      && (!query.has('flowInstanceId') || row.flowInstanceId === Number(query.get('flowInstanceId')))
      && inRange(row.createdAt.slice(0, 10), query.get('from')?.slice(0, 10) ?? null, query.get('to')?.slice(0, 10) ?? null),
    ), query)),
    route('GET', at(`${base}/workflow-deliveries/:id`), ({ params }) => ({
      delivery: deliveries.get(params[1]),
      recipients: [
        {
          recipientUserId: 1,
          inAppStatus: 'WRITTEN',
          messageId: null,
          mailStatus: 'ACCEPTED',
          mailLogId: 1,
          inAppErrorCode: null,
          mailErrorCode: null,
        },
      ],
    })),
  ]
  function workflowScenes() {
    return Array.from(scenes.values()).filter(row => row.enabled).map(({ sceneCode, nameCn, nameEn }) => ({ sceneCode, nameCn, nameEn }))
  }
  return { routes, workflowScenes }
}
