import { Buffer } from 'node:buffer'
import { Readable } from 'node:stream'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { publicationTime } from '@/views/pms/workflow/presentation'
import { createMockMiddleware } from '../mock/server.js'

let middleware

beforeEach(() => {
  vi.setSystemTime(new Date('2026-11-25T02:00:00Z'))
  middleware = createMockMiddleware()
})

afterEach(() => {
  vi.useRealTimers()
})

async function request(url, { method = 'GET', token, body } = {}) {
  const incoming = Object.assign(Readable.from(body === undefined
    ? []
    : [
        Buffer.from(JSON.stringify(body)),
      ]), {
    url,
    method,
    headers: token
      ? { authorization: `Bearer ${token}` }
      : {},
  })
  const result = {}
  await middleware(incoming, {
    writeHead(status) { result.status = status },
    end(content) { Object.assign(result, JSON.parse(content)) },
  }, () => { result.status = 204 })
  return result
}

function api(path, options) {
  return request(`/api${path}`, options)
}

async function login() {
  const response = await api('/auth/login', {
    method: 'POST',
    body: { username: 'demo', password: 'FlowDemo123', loginMethod: 'LOCAL' },
  })
  expect(response).toMatchObject({
    status: 200,
    code: 200,
    data: {
      accessToken: expect.any(String),
    },
  })
  return response.data.accessToken
}

describe('前端 Mock 开发服务器', () => {
  it('使用原有登录、权限菜单和退出接口完成会话', async () => {
    expect(await api('/auth/login/options')).toMatchObject({
      data: { captchaRequired: false, localEnabled: true, domainEnabled: false },
    })
    expect(await api('/auth/login', {
      method: 'POST',
      body: { username: 'demo', password: 'invalid', loginMethod: 'LOCAL' },
    })).toMatchObject({ status: 400 })
    const token = await login()
    expect(await api('/user/detail', { token })).toMatchObject({
      data: {
        username: 'demo',
        profile: { nickName: '演示用户' },
      },
    })
    const { data } = await api('/role/permissions/tree', { token })
    expect(data.find(group => group.code === 'Workbench').children.map(item => item.path)).toEqual([
      '/workbench/todos',
      '/workbench/todo-calendar',
      '/workbench/workflows',
      '/workbench/workflow-catalog',
      '/workbench/products',
    ])
    expect(await api('/auth/logout', { method: 'POST', token })).toMatchObject({ status: 200 })
    expect(await api('/user/detail', { token })).toMatchObject({ status: 401 })
  })

  it('分页和筛选流程模板，并支持读取历史与详情', async () => {
    const token = await login()
    const result = await api('/workflow/definitions?keyword=费用&status=PUBLISHED&pageNo=1&pageSize=1', { token })
    expect(result.data).toMatchObject({
      pageData: [
        { id: 1, name: '费用报销', status: 'PUBLISHED' },
      ],
      total: 1,
    })
    expect((await api('/workflow/definitions/1', { token })).data.nodes.map(node => node.key))
      .toEqual(['fill', 'review'])
    expect((await api('/workflow/definitions/1/history', { token })).data.total).toBeGreaterThan(0)
    expect((await api('/workflow/catalog/page?pageNo=2&pageSize=1', { token })).data)
      .toMatchObject({
        pageData: [
          expect.any(Object),
        ],
        total: 2,
      })
  })

  it('模板发布时间可以由现有页面的北京时间格式化函数渲染', async () => {
    const token = await login()
    const { data } = await api('/workflow/definitions/1', { token })
    expect(publicationTime(data.publishedAt)).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/)
  })

  it('发起后同步待办，推进两步并保存流程结果和历史', async () => {
    const token = await login()
    const body = { definitionId: 1, title: '差旅费审核', requestKey: 'mock-start-test' }
    const started = await api('/workflow/instances', { method: 'POST', token, body })
    const { instance, tasks: [task] } = started.data
    const duplicate = await api('/workflow/instances', { method: 'POST', token, body })
    expect(duplicate.data.instance.id).toBe(instance.id)
    expect((await api('/workbench/todos?scope=mine&origin=FLOW&keyword=差旅费', { token })).data)
      .toMatchObject({
        total: 1,
        records: [
          { id: task.todoId, sourceUrl: `/workbench/workflow-task/${instance.id}?mode=task&todo=${task.todoId}` },
        ],
      })
    const calendar = '/workbench/todos/calendar?fromDate=2026-11-25&toDate=2026-11-27&origin=FLOW&keyword=差旅费'
    expect(await api(calendar, { token })).toMatchObject({
      status: 200,
      data: [
        { id: task.todoId, status: 'OPEN', dueDate: '2026-11-26' },
      ],
    })

    const submit = `/workflow/instances/${instance.id}/actions/submit`
    const first = {
      requestKey: 'mock-submit-test',
      version: instance.version,
      todoId: task.todoId,
      fields: { SUMMARY: '客户现场会议' },
    }
    const advanced = await api(submit, { method: 'POST', token, body: first })
    expect(advanced.data).toMatchObject({
      instance: { currentStepNo: 2, status: 'RUNNING' },
      tasks: [
        { nodeKey: 'review', canSubmit: true },
      ],
      values: { SUMMARY: '客户现场会议' },
    })
    expect((await api(submit, { method: 'POST', token, body: first })).data.instance.version)
      .toBe(advanced.data.instance.version)
    expect(await api(submit, {
      method: 'POST',
      token,
      body: { ...first, requestKey: 'other' },
    }))
      .toMatchObject({ status: 409 })
    expect((await api(`/workbench/todos/${task.todoId}`, { token })).data.status).toBe('DONE')

    const completed = await api(submit, {
      method: 'POST',
      token,
      body: {
        requestKey: 'mock-approve-test',
        version: advanced.data.instance.version,
        todoId: advanced.data.tasks[0].todoId,
        opinion: '同意',
      },
    })
    expect(completed.data).toMatchObject({
      instance: { status: 'DONE', currentNodes: [] },
      tasks: [],
    })
    expect((await api('/workflow/instances?scope=DONE&title=差旅费', { token })).data)
      .toMatchObject({
        total: 1,
        pageData: [
          { id: instance.id, status: 'DONE' },
        ],
      })
    expect((await api(`/workflow/instances/${instance.id}/history`, { token })).data.pageData[0])
      .toMatchObject({ type: 'COMPLETED', opinion: '同意' })
    expect((await api('/workbench/todos?scope=mine&origin=FLOW&keyword=差旅费', { token })).data.total).toBe(0)
    expect(await api(calendar, { token })).toMatchObject({ status: 200, data: [] })
  })

  it('按日期和状态筛选示例待办，应用分页之前计算总数', async () => {
    const token = await login()
    const { data } = await api('/workbench/todos?scope=all&page=1&size=1', { token })
    expect(data.total).toBeGreaterThan(1)
    expect(data.records).toHaveLength(1)
    expect((await api('/workbench/todos?scope=all&fromDate=1900-01-01&toDate=1900-01-02', { token })).data.total).toBe(0)
    expect((await api('/workbench/todos?scope=all&status=DONE', { token })).data.records)
      .toEqual([
        expect.objectContaining({ status: 'DONE' }),
        expect.objectContaining({ status: 'DONE' }),
      ])
  })

  it('保持未关闭和已关闭的待办分组连续', async () => {
    const token = await login()
    const { data } = await api('/workbench/todos?scope=all', { token })
    expect(data.records.map(todo => todo.status)).toEqual([
      'OPEN',
      'OPEN',
      'OPEN',
      'OPEN',
      'DONE',
      'DONE',
    ])
  })

  it('日历返回相交计划和范围内截止日，按完成状态切换显示', async () => {
    const token = await login()
    const path = '/workbench/todos/calendar?scope=mine&origin=MANUAL'
    expect(await api(`${path}&fromDate=2026-11-24&toDate=2026-11-24`, { token })).toMatchObject({
      status: 200,
      data: [
        { title: '整理本周项目进展', startDate: '2026-11-24', endDate: '2026-11-26' },
      ],
    })
    const today = `${path}&fromDate=2026-11-25&toDate=2026-11-25`
    expect((await api(today, { token })).data.map(todo => todo.status)).toEqual(['OPEN'])
    const { data } = await api(`${today}&includeCompleted=true`, { token })
    expect(data.map(todo => todo.status)).toEqual(['OPEN', 'DONE'])
    expect(data[1]).toEqual({
      id: 2,
      title: '确认演示环境访问权限',
      status: 'DONE',
      startDate: null,
      endDate: null,
      dueDate: '2026-11-25',
    })
    expect(await api(`${path}&fromDate=2026-10-01&toDate=2026-10-31&includeCompleted=true`, { token }))
      .toMatchObject({ status: 200, data: [] })
  })

  it('日历区分发起人与负责人，待办详情读取同一条记录', async () => {
    const token = await login()
    const path = '/workbench/todos/calendar?origin=MANUAL&fromDate=2026-11-25&toDate=2026-11-25'
    expect((await api(`${path}&scope=mine`, { token })).data.map(todo => todo.id)).toEqual([1])
    const { data } = await api(`${path}&scope=created`, { token })
    expect(data.map(todo => todo.id)).toEqual(expect.arrayContaining([1, 4]))
    const { data: detail } = await api('/workbench/todos/4', { token })
    expect(detail).toMatchObject({ title: data.find(todo => todo.id === 4).title, assigneeName: '示例审批人' })
  })

  it('日历跳转的未排期待办沿用包含已完成的筛选', async () => {
    const token = await login()
    const path = '/workbench/todos?scope=mine&origin=MANUAL&status=UNSCHEDULED'
    expect((await api(`${path}&includeCompleted=false`, { token })).data.records.map(todo => todo.id)).toEqual([3])
    expect((await api(`${path}&includeCompleted=true`, { token })).data.records.map(todo => todo.id)).toEqual([3, 5])
  })

  it.each([
    'fromDate=2026-11-25',
    'fromDate=2026-02-30&toDate=2026-03-01',
    'fromDate=2026-11-25&toDate=2026-11-24',
    'fromDate=2026-11-01&toDate=2026-12-13',
  ])('日历拒绝无效日期区间 %s', async (query) => {
    const token = await login()
    expect(await api(`/workbench/todos/calendar?${query}`, { token })).toMatchObject({ status: 400, code: 400 })
  })

  it('提示未实现的 API 并将其他请求交回 Vite', async () => {
    expect(await api('/unknown')).toMatchObject({ status: 501, message: 'Mock 未实现 GET /api/unknown' })
    expect((await request('/apiculture')).status).toBe(204)
  })

  it('将无效请求目标交回 Vite 处理', async () => {
    expect((await request('//[invalid')).status).toBe(204)
  })
})
