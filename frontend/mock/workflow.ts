import type { WorkflowDefinition, WorkflowNotificationScene } from '../src/views/pms/workflow/api'
import type { Todo } from '../src/views/workbench/api'
import type { Detail, History, Scope, Task } from '../src/views/workbench/workflow/api'
import type { MockDirectory } from './admin/directory'
import type { MockFields } from './admin/fields'
import type { MockRoute } from './protocol'
import { validateFieldValue } from './admin/fields.ts'
import { createWorkflowDefinitions } from './admin/workflow-definitions.ts'
import { baseTodo, mockTime } from './fixtures.ts'
import { get, inRange, matches, MockError, object, workflowPage } from './protocol.ts'
import { demoRole, demoUser } from './session.ts'

interface Run {
  detail: Detail
  history: History[]
}

const instances = '/workflow/instances'
const demoRoleNames = { [demoRole.code]: demoRole.name }

function requiredText(value: unknown, name: string) {
  if (typeof value !== 'string' || !value.trim())
    throw new MockError(400, `请填写${name}`)
  return value.trim()
}

function remember(
  requests: Map<string, { id: number, signature: string }>,
  body: Record<string, unknown>,
  action: () => Run,
) {
  const requestKey = requiredText(body.requestKey, '请求标识')
  const signature = JSON.stringify(body)
  const previous = requests.get(requestKey)
  if (previous) {
    if (previous.signature !== signature)
      throw new MockError(409, '请求标识已用于其他内容')
    return previous.id
  }
  const run = action()
  const { id } = run.detail.instance
  requests.set(requestKey, { id, signature })
  return id
}

function fieldValues(fields: unknown, task: Task, detail: Detail) {
  const values = { ...detail.values }
  const patch = fields == null
    ? {}
    : object(fields)
  for (const [key, value] of Object.entries(patch)) {
    if (!task.editableFields.includes(key))
      throw new MockError(400, '流程字段不允许修改或格式有误')
    if (value === null || typeof value === 'string' || typeof value === 'boolean')
      values[key] = value
    else if (Array.isArray(value) && value.every((item): item is string => typeof item === 'string'))
      values[key] = value
    else
      throw new MockError(400, '流程字段格式有误')
  }
  for (const field of detail.definition.fields) {
    const required = task.requiredFields.includes(field.code)
    if (!required && !Object.hasOwn(patch, field.code))
      continue
    if (field.config && !validateFieldValue({ ...field.config, required }, values[field.code], field.options ?? []))
      throw new MockError(400, `请检查字段「${field.config.name}」`)
  }
  return values
}

export function createWorkflow(todos: Map<number, Todo>, fields: MockFields, directory: MockDirectory, scenes: () => WorkflowNotificationScene[]) {
  const runs = new Map<number, Run>()
  const { definitions, routes: definitionRoutes } = createWorkflowDefinitions(
    directory,
    fields,
    scenes,
    id => Array.from(runs.values()).reverse().map(run => run.detail.instance).filter(item => item.definitionId === id),
  )
  const startRequests = new Map<string, { id: number, signature: string }>()
  const actionRequests = new Map<number, Map<string, { id: number, signature: string }>>()
  let nextRunId = 1
  let nextTodoId = Math.max(0, ...todos.keys()) + 1
  let nextEventId = 1

  function record(run: Run, type: string, task: Task | undefined, now: string, additions: Partial<History> = {}) {
    run.history.unshift({
      id: nextEventId++,
      type,
      nodeKey: task?.nodeKey ?? null,
      targetNodeKey: null,
      actorName: demoUser.name,
      identity: task?.identity ?? null,
      reason: null,
      opinion: null,
      createdAt: now,
      attachments: {},
      attachmentNames: {},
      ...additions,
    })
  }

  function activate(detail: Detail, step: number, now: string) {
    const node = detail.definition.nodes[step - 1]
    const editable = node.editableFields ?? (step === 1
      ? detail.definition.fields.map(field => field.code)
      : [])
    const id = nextTodoId++
    const { instance } = detail
    detail.nodes.push({
      id,
      nodeKey: node.key,
      stageNo: step,
      attemptNo: detail.nodes.filter(item => item.nodeKey === node.key).length + 1,
      status: 'ACTIVE',
      roleName: demoRole.name,
      processedNames: [],
      assigneeNames: [demoUser.name],
    })
    detail.tasks = [
      {
        id,
        todoId: id,
        nodeKey: node.key,
        identity: 'PRIMARY',
        editableFields: editable,
        requiredFields: node.requiredFields.filter(code => editable.includes(code)),
        canSubmit: true,
        rejectTargets: step > 1
          ? [detail.definition.nodes[step - 2].key]
          : [],
      },
    ]
    instance.currentStepNo = step
    instance.currentNodes = [
      {
        nodeKey: node.key,
        title: node.title,
        status: 'ACTIVE',
        roleName: demoRole.name,
        assigneeNames: [demoUser.name],
      },
    ]
    detail.canRecall = step > 1
    todos.set(id, {
      ...baseTodo(id, `${instance.title} · ${node.title}`, now, node.dueOffsetDays ?? 1),
      origin: 'FLOW',
      sourceModule: 'WORKFLOW',
      businessType: 'WORKFLOW',
      businessId: String(instance.id),
      sourceLabel: node.title,
      sourceUrl: `/workbench/workflow-task/${instance.id}?mode=task&todo=${id}`,
    })
  }

  function start(definition: WorkflowDefinition, title: string, now = mockTime()): Run {
    const id = nextRunId++
    const detail: Detail = {
      instance: {
        id,
        definitionId: definition.id,
        title,
        ownerName: demoUser.name,
        currentStepNo: 1,
        status: 'RUNNING',
        version: 1,
        startedAt: now,
        currentNodes: [],
      },
      definition: structuredClone(definition),
      values: {},
      nodes: [],
      tasks: [],
      canRecall: false,
      canCancel: true,
      canRetry: false,
      attachmentNames: {},
      sourceUrl: null,
      roleNames: demoRoleNames,
      plannedAssignees: Object.fromEntries(definition.nodes.map(node => [
        node.key,
        [demoUser.name],
      ])),
    }
    const run: Run = { detail, history: [] }
    activate(detail, 1, now)
    record(run, 'STARTED', undefined, now, { targetNodeKey: detail.tasks[0].nodeKey })
    runs.set(id, run)
    return run
  }

  function closeNode(detail: Detail, status: Detail['nodes'][number]['status'], now: string) {
    const node = detail.nodes.at(-1)!
    node.status = status
    if (status === 'DONE' || status === 'RETURNED')
      node.processedNames = [demoUser.name]
    const todo = get(todos, String(detail.tasks[0].todoId))
    todo.status = status === 'DONE' || status === 'RETURNED'
      ? 'DONE'
      : 'CANCELLED'
    todo.closedAt = now
    todo.version++
    detail.instance.currentNodes = []
    detail.tasks = []
    detail.canRecall = false
    detail.canCancel = false
  }

  function act(run: Run, action: string, body: Record<string, unknown>) {
    const { detail } = run
    const { instance, tasks: [task] } = detail
    if (body.version !== instance.version || instance.status !== 'RUNNING')
      throw new MockError(409, '流程状态已变化，请刷新后重试')
    const now = mockTime()
    const step = instance.currentStepNo
    const opinion = typeof body.opinion === 'string' && body.opinion.trim()
      ? body.opinion
      : null
    if ((action === 'submit' || action === 'reject') && body.todoId !== task.todoId)
      throw new MockError(409, '当前流程待办已变化，请刷新后重试')

    if (action === 'submit') {
      detail.values = fieldValues(body.fields, task, detail)
      closeNode(detail, 'DONE', now)
      if (step === detail.definition.nodes.length) {
        instance.status = 'DONE'
        record(run, 'COMPLETED', task, now, { opinion })
      }
      else {
        activate(detail, step + 1, now)
        detail.canCancel = true
        record(run, 'ADVANCED', task, now, { targetNodeKey: detail.tasks[0].nodeKey, opinion })
      }
    }
    else if (action === 'cancel') {
      const reason = requiredText(body.reason, '取消原因')
      closeNode(detail, 'CANCELLED', now)
      instance.status = 'CANCELLED'
      record(run, 'CANCELLED', undefined, now, { nodeKey: task.nodeKey, reason })
    }
    else {
      const rejecting = action === 'reject'
      const target = task.rejectTargets[0]
      if (!target || (body.targetNodeKey != null && body.targetNodeKey !== target))
        throw new MockError(409, '当前流程节点不支持该操作')
      const reason = rejecting
        ? requiredText(body.reason, '驳回原因')
        : null
      closeNode(detail, rejecting ? 'RETURNED' : 'WITHDRAWN', now)
      activate(detail, step - 1, now)
      detail.canCancel = true
      record(run, rejecting ? 'RETURNED' : 'RECALLED', task, now, { targetNodeKey: target, reason, opinion })
    }

    instance.version++
    return run
  }

  start(definitions.get(2), '采购演示设备', mockTime(-1))

  const routes: MockRoute[] = [
    ...definitionRoutes,
    {
      method: 'GET',
      path: instances,
      handle: ({ query }) => {
        const all = [
          ...runs.values(),
        ].reverse().filter(({ detail: { instance } }) =>
          matches(instance.title, query.get('title'))
          && matches(instance.ownerName, query.get('ownerName'))
          && inRange(instance.startedAt.slice(0, 10), query.get('startedFrom'), query.get('startedTo')),
        )
        const groups = {
          MINE: all.filter(run => run.detail.tasks.length > 0),
          STARTED: all,
          APPROVED: all.filter(run => run.detail.nodes.some(node => node.processedNames.length > 0)),
          DONE: all.filter(run => run.detail.instance.status === 'DONE'),
          ALL: all,
        } satisfies Record<Scope, Run[]>
        const scope = query.get('scope') ?? 'MINE'
        const group = Object.hasOwn(groups, scope)
          ? groups[scope as Scope]
          : groups.MINE
        return {
          ...workflowPage(group.map(run => run.detail.instance), query),
          scopeCounts: {
            MINE: groups.MINE.length,
            STARTED: groups.STARTED.length,
            APPROVED: groups.APPROVED.length,
            DONE: groups.DONE.length,
            ALL: groups.ALL.length,
          },
        }
      },
    },
    {
      method: 'POST',
      path: instances,
      handle: ({ body }) => {
        const id = remember(startRequests, body, () => {
          const definition = definitions.get(String(body.definitionId))
          if (definition.status !== 'PUBLISHED')
            throw new MockError(409, '请选择已发布的流程模板')
          if (definition.nodes.some(node => node.parentKey !== null))
            throw new MockError(501, 'Mock 运行仅支持顺序节点')
          return start(definition, requiredText(body.title, '流程标题'))
        })
        return get(runs, String(id)).detail
      },
    },
    {
      method: 'GET',
      path: /^\/workflow\/instances\/(\d+)$/,
      handle: ({ params }) => get(runs, params[1]).detail,
    },
    {
      method: 'GET',
      path: /^\/workflow\/instances\/(\d+)\/history$/,
      handle: ({ params, query }) => workflowPage(get(runs, params[1]).history, query),
    },
    {
      method: 'POST',
      path: /^\/workflow\/instances\/(\d+)\/actions\/(submit|reject|recall|cancel)$/,
      handle: ({ params, body }) => {
        const run = get(runs, params[1])
        const { id } = run.detail.instance
        let requests = actionRequests.get(id)
        if (!requests) {
          requests = new Map()
          actionRequests.set(id, requests)
        }
        remember(requests, { ...body, action: params[2] }, () => act(run, params[2], body))
        return run.detail
      },
    },
  ]
  function sceneUsage(sceneCode: string) {
    const refersToScene = (definition: WorkflowDefinition) => definition.nodes.some(node =>
      node.onApprovedSceneCode === sceneCode || node.onRejectedSceneCode === sceneCode,
    )
    return {
      publishedDefinitionCount: definitions.rows().filter(definition => definition.status === 'PUBLISHED' && refersToScene(definition)).length,
      runningInstanceCount: Array.from(runs.values()).filter(({ detail }) => detail.instance.status === 'RUNNING' && refersToScene(detail.definition)).length,
    }
  }
  return { routes, sceneUsage }
}
