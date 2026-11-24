import { Buffer } from 'node:buffer'
import { Readable } from 'node:stream'
import { beforeEach, describe, expect, it } from 'vitest'
import { createMockMiddleware } from '../mock/server.js'

let middleware
let token

async function call(path, method = 'GET', body) {
  const input = Object.assign(Readable.from(body === undefined
    ? []
    : [
        Buffer.from(JSON.stringify(body)),
      ]), {
    url: `/api${path}`,
    method,
    headers: token
      ? { authorization: `Bearer ${token}` }
      : {},
  })
  let status
  let content
  await middleware(input, {
    writeHead(value) { status = value },
    end(value) { content = value },
  }, () => {})
  return {
    status,
    ...JSON.parse(content),
  }
}

async function data(path, method, body) {
  const result = await call(path, method, body)
  expect(result.status, path).toBe(200)
  return result.data
}

beforeEach(async () => {
  middleware = createMockMiddleware()
  token = undefined
  token = (await data('/auth/login', 'POST', { username: 'demo', password: 'FlowDemo123', loginMethod: 'LOCAL' })).accessToken
})

describe('系统管理 Mock', () => {
  it('资源管理可注册和编辑菜单资源', async () => {
    const tree = await data('/permission/tree')
    const system = tree.find(item => item.code === 'SysMgt')
    expect(system.children.map(item => item.path)).toEqual([
      '/pms/resource',
      '/pms/role',
      '/pms/user',
      '/pms/param',
      '/pms/audit',
      '/pms/auth-policy',
      '/pms/department',
      '/pms/field',
      '/pms/mail',
      '/pms/notification-group',
      '/pms/open-api',
      '/pms/workflow',
    ])
    const entry = await data('/permission', 'POST', {
      name: '测试菜单',
      code: 'MockMenu',
      type: 'MENU',
      parentId: system.id,
    })
    await data(`/permission/${entry.id}`, 'PATCH', { name: '修改菜单' })
    expect((await data('/permission/tree')).find(item => item.id === system.id).children)
      .toContainEqual(expect.objectContaining({ id: entry.id, name: '修改菜单' }))
  })

  it('角色管理可授权并关联用户', async () => {
    const entry = await data('/role', 'POST', { code: 'REVIEWER', name: '审批人', permissionIds: [] })
    await data(`/role/users/add/${entry.id}`, 'PATCH', {
      userIds: [1],
    })
    expect((await data(`/user?roleId=${entry.id}&roleAssigned=true`)).pageData.map(item => item.id)).toEqual([1])
    expect((await data('/role/page?name=审批')).pageData).toContainEqual(expect.objectContaining({ code: 'REVIEWER' }))
  })

  it('用户管理创建、筛选并更新模拟用户', async () => {
    expect(await data('/user', 'POST', { username: 'mock.user', nameCn: '测试用户', enable: true })).toHaveProperty('temporaryPassword')
    const { pageData: [user] } = await data('/user?keyword=mock.user')
    await data(`/user/${user.id}`, 'PATCH', { enable: false })
    expect((await data('/user?username=mock.user')).pageData[0].enable).toBe(false)
  })

  it('参数管理在组内创建参数', async () => {
    const group = await data('/param/groups', 'POST', { code: 'MOCK', name: '演示组' })
    await data(`/param/groups/${group.id}/items`, 'POST', { code: 'EXAMPLE', name: '实例' })
    expect((await data(`/param/groups/${group.id}/items`)).pageData[0].name).toBe('实例')
  })

  it('操作日志可列出示例日志和详情', async () => {
    const { pageData: [row] } = await data('/audit/logs')
    expect(await data(`/audit/logs/${row.id}`)).toMatchObject({
      log: { id: row.id, summary: '修改参数组' },
      objects: {
        pageData: [
          expect.objectContaining({
            changes: [
              expect.objectContaining({
                before: expect.objectContaining({ present: true, value: '旧参数名' }),
                after: expect.objectContaining({ present: true, value: '演示优先级' }),
              }),
            ],
          }),
        ],
      },
    })
  })

  it('认证策略修改与还原保留版本', async () => {
    const path = '/system-policy/auth-security'
    const snapshot = await data(path)
    const config = {
      ...snapshot.config,
      login: { ...snapshot.config.login, localEnabled: false, domainEnabled: true },
    }
    const changed = await data(path, 'PUT', { revision: snapshot.revision, config })
    expect(changed.config.login.localEnabled).toBe(false)
    expect((await data(`${path}/reset`, 'POST', { revision: changed.revision })).config).toEqual(snapshot.defaults)
  })

  it('组织架构可创建部门并改派成员', async () => {
    const department = await data('/department', 'POST', {
      name: '设计组',
      parentId: null,
      sortOrder: 8,
      enabled: true,
    })
    const current = await data('/department/users/1/memberships')
    await data('/department/member-changes', 'POST', {
      action: 'ADD',
      targetDepartmentId: department.id,
      users: [
        { userId: 1, expectedVersion: current.departmentVersion },
      ],
    })
    expect((await data(`/department/members?departmentId=${department.id}`)).pageData).toContainEqual(expect.objectContaining({ id: 1 }))
  })

  it('字段管理可新增和校验字段', async () => {
    expect((await data('/field-definition/metadata')).types).toContainEqual(expect.objectContaining({ code: 'TEXT' }))
    const entry = await data('/field-definition', 'POST', {
      code: 'DEMO_LABEL',
      name: '标题',
      dataType: 'TEXT',
      enable: true,
      required: true,
    })
    expect(await data(`/field-definition/${entry.id}/validate`, 'POST', { value: 'Test' })).toMatchObject({ valid: true })
    expect(await data(`/field-definition/${entry.id}/validate`, 'POST', { value: 16 })).toMatchObject({ valid: false })
  })

  it('字段演示数据覆盖全部类型、列表翻页以及组合筛选', async () => {
    const path = '/field-definition'
    const first = await data(`${path}?pageNo=1&pageSize=20`)
    const second = await data(`${path}?pageNo=2&pageSize=20`)
    expect(first.pageData).toHaveLength(20)
    expect(second.pageData.length).toBeGreaterThan(0)
    const fields = [
      ...first.pageData,
      ...second.pageData,
    ].map(row => row.config)
    expect(new Set(fields.map(field => field.code)).size).toBe(first.total)
    expect(new Set(fields.map(field => field.dataType))).toEqual(
      new Set((await data(`${path}/metadata`)).types.map(type => type.code)),
    )
    const result = await data(`${path}?keyword=Legacy%20Source&dataType=ENUM&enable=false`)
    expect(result.pageData.map(row => row.config.code)).toEqual(['legacy_source'])
    expect((await data(`${path}?keyword=项目编号&dataType=TEXT&enable=true`)).pageData.map(row => row.config.code))
      .toEqual(['project_reference'])
  })

  it('演示字段可试测格式、边界、空值和附件引用', async () => {
    const path = '/field-definition'
    const { pageData } = await data(`${path}?pageNo=1&pageSize=100`)
    const examples = [
      {
        code: 'contact_email',
        valid: ['ops@example.invalid'],
        invalid: ['ops', null],
      },
      {
        code: 'reference_url',
        valid: ['https://example.invalid/docs', null],
        invalid: ['ftp://example.invalid/docs'],
      },
      {
        code: 'project_reference',
        valid: ['PRJ-000123'],
        invalid: ['PRJ-123'],
      },
      {
        code: 'request_title',
        valid: ['华东仓 配送 2，已确认！'],
        invalid: ['A', '配送🙂'],
      },
      {
        code: 'headcount',
        valid: ['1', '999'],
        invalid: ['0', '1000'],
      },
      {
        code: 'temperature_delta',
        valid: ['-50', '0', '50'],
        invalid: ['-51'],
      },
      {
        code: 'external_sequence',
        valid: ['-9223372036854775808', '9223372036854775807'],
        invalid: ['9223372036854775808'],
      },
      {
        code: 'expense_amount',
        valid: ['0.01', '99999999.99'],
        invalid: ['0', '12.345', '100000000.00'],
      },
      {
        code: 'discount_rate',
        valid: ['0.00', '1.00', null],
        invalid: ['-0.01', '1.01'],
      },
      {
        code: 'exchange_rate',
        valid: ['7.123456'],
        invalid: ['7.1234567'],
      },
      {
        code: 'approval_confirmed',
        valid: [true, false],
        invalid: ['false', null],
      },
      {
        code: 'auto_notify',
        valid: [true, false, null],
        invalid: ['true'],
      },
      {
        code: 'delivery_date',
        valid: ['2026-11-01', '2026-12-31'],
        invalid: ['2026-12-32', '2027-01-01'],
      },
      {
        code: 'contract_start_date',
        valid: ['2028-02-29', null],
        invalid: ['2027-02-29', '2025-12-31'],
      },
      {
        code: 'appointment_time',
        valid: ['2026-11-25T09:00:00.000'],
        invalid: ['2026-11-25T08:59:59.999'],
      },
      {
        code: 'processed_at',
        valid: ['2026-11-25T23:59:59', null],
        invalid: ['2026-11-26T00:00:00'],
      },
      {
        code: 'receipt_files',
        valid: [
          [
            { documentId: '42', versionId: '106' },
            { documentId: '9223372036854775807', versionId: '107' },
          ],
        ],
        invalid: [
          null,
          [],
          [
            { documentId: '42' },
          ],
          [
            { documentId: 42, versionId: '106' },
          ],
          [
            { documentId: '9223372036854775808', versionId: '107' },
          ],
          [
            { documentId: '42', versionId: '106' },
            { documentId: '42', versionId: '107' },
          ],
        ],
      },
      {
        code: 'supporting_files',
        valid: [null, []],
        invalid: [
          { documentId: '42', versionId: '106' },
        ],
      },
    ]
    for (const { code, valid, invalid } of examples) {
      const field = pageData.find(row => row.config.code === code)
      expect(field, code).toBeDefined()
      for (const [expected, values] of [
        [true, valid],
        [false, invalid],
      ]) {
        for (const value of values) {
          expect(await data(`${path}/${field.id}/validate`, 'POST', { value }), `${code} ${JSON.stringify(value)}`)
            .toMatchObject({ valid: expected })
        }
      }
    }
  })

  it('枚举演示字段使用共享字典并覆盖废弃选项、停用字典和多选数量', async () => {
    const path = '/field-definition'
    const { pageData } = await data(`${path}?pageNo=1&pageSize=100`)
    const field = code => pageData.find(row => row.config.code === code)
    const validate = (code, value) => data(`${path}/${field(code).id}/validate`, 'POST', { value })
    const { paramGroupId } = field('delivery_method').config
    const options = await data(`${path}/dictionaries/${paramGroupId}/options`)
    expect(options).toContainEqual(expect.objectContaining({ code: 'LEGACY_POST', enable: false }))
    expect((await data(`/param/groups/${paramGroupId}/items`)).pageData.map(row => row.code))
      .toEqual(options.map(row => row.code))
    expect(await validate('delivery_method', 'COURIER')).toMatchObject({ valid: true })
    expect(await validate('delivery_method', 'LEGACY_POST')).toMatchObject({ valid: false })
    expect(await validate('legacy_delivery_method', 'COURIER')).toMatchObject({ valid: true })
    expect(await validate('legacy_source', 'OLD_PORTAL')).toMatchObject({ valid: false })
    expect(await validate('legacy_source', null)).toMatchObject({ valid: true })
    expect(await validate('request_tags', ['FINANCE', 'URGENT'])).toMatchObject({ valid: true })
    for (const value of [
      ['FINANCE'],
      [
        'FINANCE',
        'URGENT',
        'CONTRACT',
        'INTERNAL',
      ],
      ['FINANCE', 'FINANCE'],
      ['FINANCE', 'RETIRED'],
    ]) {
      expect(await validate('request_tags', value)).toMatchObject({ valid: false })
    }
    expect(await validate('optional_tags', [])).toMatchObject({ valid: true })
  })

  it('临时试填字段可编辑、停用和删除，操作后列表能回查', async () => {
    const path = '/field-definition'
    const search = `${path}?keyword=preview_note`
    const { pageData: [field] } = await data(search)
    await data(`${path}/${field.id}`, 'PUT', { ...field.config, enable: false, name: '已修改的试填说明' })
    expect((await data(`${search}&enable=false`)).pageData[0].config).toMatchObject({ name: '已修改的试填说明', enable: false })
    await data(`${path}/${field.id}`, 'DELETE')
    expect((await data(search)).total).toBe(0)
  })

  it('邮件管理可显示邮箱、模板和发送记录', async () => {
    expect((await data('/mail/accounts')).records).not.toHaveLength(0)
    expect((await data('/mail/templates')).records).not.toHaveLength(0)
    expect((await data('/mail/logs')).records).not.toHaveLength(0)
  })

  it('同一次邮件试发只写入一条本地日志', async () => {
    const body = {
      templateCode: 'DEMO_NOTICE',
      recipients: [
        { address: 'recipient@example.invalid' },
      ],
      idempotencyKey: 'same-mail-request',
    }
    const first = await data('/mail/send', 'POST', body)
    expect(await data('/mail/send', 'POST', body)).toEqual(first)
    expect(await data(`/mail/logs/${first.logId}`)).toMatchObject({ status: 'ACCEPTED', recipientCount: 1 })
    expect((await data('/mail/logs')).total).toBe(2)
  })

  it('通知群组可读取群组和场景', async () => {
    expect((await data('/notification-groups')).pageData).not.toHaveLength(0)
    expect((await data('/notification-groups/scenes')).pageData).not.toHaveLength(0)
  })

  it('工作流引用通知场景后更新已发布和运行中的使用数', async () => {
    const sceneCode = 'DEMO_WORKFLOW_APPROVED'
    const draft = await data('/workflow/definitions', 'POST', {
      code: 'MOCK_NOTICE',
      name: '通知流程',
      fields: [],
      nodes: [
        {
          key: 'review',
          title: '审批',
          order: 1,
          mode: 'PRIMARY',
          onApprovedSceneCode: sceneCode,
        },
      ],
    })
    await data(`/workflow/definitions/${draft.id}/publish`, 'POST', { version: draft.version })
    await data('/workflow/instances', 'POST', { definitionId: draft.id, title: draft.name, requestKey: 'notice-start' })
    expect(await data(`/notification-groups/scenes/workflow/${sceneCode}/usages`)).toEqual({ publishedDefinitionCount: 1, runningInstanceCount: 1 })
  })

  it('字段校验遵循所保存的文本正则', async () => {
    const entry = await data('/field-definition', 'POST', {
      code: 'MOCK_PATTERN',
      name: '文本规则',
      dataType: 'TEXT',
      textPattern: 'A+',
    })
    expect(await data(`/field-definition/${entry.id}/validate`, 'POST', { value: 'AA' })).toMatchObject({ valid: true })
    expect(await data(`/field-definition/${entry.id}/validate`, 'POST', { value: 'AB' })).toMatchObject({ valid: false })
  })

  it('通知群组预览合并显式用户与部门成员', async () => {
    const result = await data('/notification-groups/preview', 'POST', {
      userIds: [1, 2],
      departments: [
        { departmentId: 3, includeDescendants: true },
      ],
      pageNo: 1,
      pageSize: 10,
    })
    expect(result.pageData.map(row => row.userId)).toEqual([1, 2])
    expect(result.total).toBe(2)
  })

  it('开放 API 可查询演示应用和接口', async () => {
    expect((await data('/admin/open-api/apps')).pageData).not.toHaveLength(0)
    expect((await data('/admin/open-api/endpoints')).pageData).not.toHaveLength(0)
  })

  it('开放 API 密钥激活后只查询到版本状态', async () => {
    const path = '/admin/open-api/apps/1/secrets'
    const key = await data(path, 'POST')
    expect(key.clientSecret).toMatch(/^mock-only-/)
    await data(`${path}/${key.versionNo}/activate`, 'POST')
    const [version] = await data(path)
    expect(version).toMatchObject({ versionNo: key.versionNo, status: 'ACTIVE' })
    expect(version).not.toHaveProperty('clientSecret')
  })

  it('流程模板可保存草稿并发布到流程目录', async () => {
    const template = await data('/workflow/definitions/1')
    const draft = await data('/workflow/definitions', 'POST', {
      code: 'MOCK_COPY',
      name: '演示副本',
      fields: ['SUMMARY'],
      nodes: template.nodes,
    })
    expect(draft.status).toBe('DRAFT')
    expect((await data(`/workflow/definitions/${draft.id}/publish`, 'POST', { version: draft.version })).status).toBe('PUBLISHED')
    expect((await data('/workflow/catalog/page')).pageData).toContainEqual(expect.objectContaining({ id: draft.id }))
  })

  it('新模板的首节点可以填写字段管理中创建的布尔字段', async () => {
    await data('/field-definition', 'POST', { code: 'ACK', name: '已确认', dataType: 'BOOLEAN' })
    const draft = await data('/workflow/definitions', 'POST', {
      code: 'MOCK_ACK',
      name: '确认示例',
      fields: ['ACK'],
      nodes: [
        {
          key: 'confirm',
          title: '确认',
          order: 1,
          mode: 'PRIMARY',
          editableFields: null,
          requiredFields: ['ACK'],
        },
      ],
    })
    await data(`/workflow/definitions/${draft.id}/publish`, 'POST', { version: draft.version })
    const { instance, tasks: [task] } = await data('/workflow/instances', 'POST', { definitionId: draft.id, title: draft.name, requestKey: 'ack-start' })
    const result = await data(`/workflow/instances/${instance.id}/actions/submit`, 'POST', {
      requestKey: 'ack-submit',
      version: instance.version,
      todoId: task.todoId,
      fields: { ACK: false },
    })
    expect(result.values).toEqual({ ACK: false })
    expect(result.instance.status).toBe('DONE')
  })
})
