import type { WorkflowDefinition, WorkflowNode, WorkflowNotificationScene } from '../../src/views/pms/workflow/api'
import type { Instance } from '../../src/views/workbench/workflow/api'
import type { MockDirectory } from './directory'
import type { MockFields } from './fields'
import { exampleDefinitions } from '../fixtures.ts'
import { MockError, object, workflowPage } from '../protocol.ts'
import { at, checkVersion, collection, matchesAny, number, route, text, unique } from './common.ts'

function codes(value: unknown): string[] {
  return Array.isArray(value)
    ? [
        ...new Set(value.filter((item): item is string => typeof item === 'string' && !!item)),
      ]
    : []
}

function latest(rows: WorkflowDefinition[]) {
  const selected = new Map<string, WorkflowDefinition>()
  for (const row of rows) {
    if ((selected.get(row.code)?.revision ?? 0) < row.revision)
      selected.set(row.code, row)
  }
  return Array.from(selected.values())
}

export function createWorkflowDefinitions(
  directory: MockDirectory,
  { fields, dictionaryOptions }: MockFields,
  scenes: () => WorkflowNotificationScene[],
  instances: (id: number) => Instance[],
) {
  const base = '/workflow/definitions'
  const definitions = collection(exampleDefinitions())
  function bindings(value: unknown) {
    return codes(value).map((code) => {
      const row = fields.rows().find(field => field.config.code === code)
      if (!row)
        throw new MockError(400, '找不到所选字段')
      const config = structuredClone(row.config)
      return {
        code,
        config,
        options: config.paramGroupId === null
          ? []
          : dictionaryOptions(config.paramGroupId),
      }
    })
  }
  function nodes(value: unknown): WorkflowNode[] {
    if (!Array.isArray(value) || !value.length)
      throw new MockError(400, '请添加流程节点')
    const entries: WorkflowNode[] = value.map((item) => {
      const row = object(item)
      const mode = row.mode
      return {
        key: text(row.key),
        parentKey: text(row.parentKey) || null,
        order: number(row.order, 1),
        title: text(row.title),
        roleCode: text(row.roleCode) || null,
        roleCodes: codes(row.roleCodes),
        mode: mode === 'PRIMARY' || mode === 'BROADCAST' || mode === 'ALL' || mode === 'MULTI_ALL'
          ? mode
          : null,
        editableFields: row.editableFields === null
          ? null
          : codes(row.editableFields),
        requiredFields: codes(row.requiredFields),
        dueOffsetDays: number(row.dueOffsetDays, -1) < 0
          ? null
          : number(row.dueOffsetDays, 0),
        onApprovedSceneCode: text(row.onApprovedSceneCode) || null,
        onRejectedSceneCode: text(row.onRejectedSceneCode) || null,
      }
    })
    const roots = entries.filter(row => row.parentKey === null).sort((a, b) => a.order - b.order)
    if (entries.some(row => !row.key || !row.title || (row.parentKey !== null && !roots.some(root => root.key === row.parentKey)))
      || new Set(entries.map(row => row.key)).size !== entries.length || !roots.length) {
      throw new MockError(400, '请检查节点名称、编码和上下级关系')
    }
    return roots.flatMap(root => [
      root,
      ...entries.filter(row => row.parentKey === root.key).sort((a, b) => a.order - b.order),
    ])
  }
  function write(body: Record<string, unknown>) {
    const name = text(body.name).trim()
    if (!name)
      throw new MockError(400, '请输入流程模板名称')
    return {
      name,
      fields: bindings(body.fields),
      nodes: nodes(body.nodes),
    }
  }
  const publishable = () => latest(definitions.rows().filter(item => item.status === 'PUBLISHED'))
  const routes = [
    route('GET', '/workflow/catalog', publishable),
    route('GET', '/workflow/catalog/page', ({ query }) => workflowPage(publishable(), query)),
    route('GET', base, ({ query }) => {
      const rows = query.get('latestOnly') === 'true'
        ? latest(definitions.rows())
        : definitions.rows()
      return workflowPage(rows.filter(row =>
        matchesAny(query.get('keyword'), row.name, row.code) && (!query.has('status') || row.status === query.get('status')),
      ), query)
    }),
    route('GET', at(`${base}/:id`), ({ params }) => definitions.get(params[1])),
    route('GET', at(`${base}/:id/history`), ({ params, query }) => {
      const { code } = definitions.get(params[1])
      return workflowPage(definitions.rows().filter(row => row.code === code).sort((a, b) => b.revision - a.revision), query)
    }),
    route('GET', at(`${base}/:id/instances`), ({ params, query }) => workflowPage(instances(definitions.get(params[1]).id), query)),
    route('GET', `${base}/roles`, () => directory.roles.rows().filter(role => role.enable).map(({ id, code, name, nameEn }) => ({
      code,
      name,
      nameEn,
      hasEnabledMembers: directory.users.rows().some(user => user.enable && user.roleIds.includes(id)),
    }))),
    route('GET', `${base}/notification-scenes`, scenes),
    route('GET', `${base}/fields`, ({ query }) => workflowPage(fields.rows().filter(({ config }) =>
      config.enable && matchesAny(query.get('keyword'), config.code, config.name, config.nameEn),
    ), query)),
    route('GET', `${base}/fields/by-code`, ({ query }) => fields.rows().filter(field => query.get('codes')?.split(',').includes(field.config.code))),
    route('POST', base, ({ body }) => {
      const sourceId = number(body.sourceDefinitionId, 0)
      const code = text(body.code).trim()
      if (!sourceId)
        unique(definitions.rows(), code, row => row.code)
      else if (definitions.get(sourceId).code !== code)
        throw new MockError(400, '模板版本必须沿用原编码')
      const revision = Math.max(0, ...definitions.rows().filter(row => row.code === code).map(row => row.revision)) + 1
      return definitions.create({
        code,
        revision,
        version: 1,
        status: 'DRAFT',
        publishedAt: null,
        ...write(body),
      })
    }),
    route('PATCH', at(`${base}/:id`), (request) => {
      const row = definitions.get(request.params[1])
      checkVersion(request, row.version)
      if (row.status !== 'DRAFT')
        throw new MockError(409, '仅草稿可编辑')
      return Object.assign(row, write(request.body), { version: row.version + 1 })
    }),
    route('POST', /^\/workflow\/definitions\/(\d+)\/(publish|retire)$/, (request) => {
      const row = definitions.get(request.params[1])
      checkVersion(request, row.version)
      if (request.params[2] === 'publish' && row.status === 'DRAFT') {
        Object.assign(row, {
          status: 'PUBLISHED',
          publishedAt: new Date().toISOString().slice(0, -1),
        })
      }
      else if (request.params[2] === 'retire' && row.status === 'PUBLISHED') {
        row.status = 'RETIRED'
      }
      else {
        throw new MockError(409, '模板状态已变化')
      }
      row.version++
      return row
    }),
  ]
  return { definitions, routes }
}
