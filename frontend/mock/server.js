import { Buffer } from 'node:buffer'
import { auditRoutes } from './admin/audit.ts'
import { authPolicyRoutes } from './admin/auth-policy.ts'
import { MockFile } from './admin/common.ts'
import { departmentRoutes } from './admin/departments.ts'
import { createDirectory } from './admin/directory.ts'
import { createFields } from './admin/fields.ts'
import { createMail } from './admin/mail.ts'
import { createNotifications } from './admin/notifications.ts'
import { openApiRoutes } from './admin/open-api.ts'
import { createParams } from './admin/params.ts'
import { createResources } from './admin/resources.ts'
import { roleRoutes } from './admin/roles.ts'
import { userRoutes } from './admin/users.ts'
import { productCenterRoutes } from './product-center.ts'
import { MockError, object } from './protocol.ts'
import { createSession } from './session.ts'
import { createTodos } from './todos.ts'
import { createWorkflow } from './workflow.ts'

async function requestBody(request) {
  if (!['POST', 'PUT', 'PATCH'].includes(request.method))
    return {}
  const chunks = []
  let size = 0
  for await (const chunk of request.iterator({ destroyOnReturn: false })) {
    size += chunk.length
    if (size > 128 * 1024) {
      request.resume()
      throw new MockError(413, 'Mock 请求体过大')
    }
    chunks.push(chunk)
  }
  if (!size)
    return {}

  try {
    return object(JSON.parse(Buffer.concat(chunks).toString('utf8')))
  }
  catch {
    throw new MockError(400, '请求体必须为 JSON 对象')
  }
}

function send(response, status, data) {
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  })
  response.end(JSON.stringify(data))
}

export function createMockMiddleware() {
  const resources = createResources()
  const directory = createDirectory()
  const params = createParams()
  const fields = createFields(params)
  const mail = createMail()
  const { routes: sessionRoutes, tokens } = createSession(resources)
  const { routes: todoRoutes, todos } = createTodos(directory.users.get(2))
  const notifications = createNotifications(directory, mail)
  const workflow = createWorkflow(todos, fields, directory, notifications.workflowScenes)
  const routes = [
    ...sessionRoutes,
    ...resources.routes,
    ...roleRoutes(directory),
    ...userRoutes(directory),
    ...params.routes,
    ...auditRoutes(),
    ...authPolicyRoutes(),
    ...departmentRoutes(directory),
    ...fields.routes,
    ...mail.routes,
    ...notifications.routes(workflow.sceneUsage),
    ...openApiRoutes(directory),
    ...todoRoutes,
    ...productCenterRoutes(),
    ...workflow.routes,
  ]

  return async (request, response, next) => {
    const url = URL.parse(request.url ?? '/', 'http://localhost')
    if (!url || (url.pathname !== '/api' && !url.pathname.startsWith('/api/'))) {
      next()
      return
    }
    const path = url.pathname.slice(4)
    for (const route of routes) {
      if (route.method !== request.method)
        continue
      const params = typeof route.path === 'string'
        ? (path === route.path
            ? [path]
            : null)
        : path.match(route.path)
      if (!params)
        continue

      try {
        const token = request.headers.authorization?.match(/^Bearer (\S+)$/i)?.[1] ?? ''
        if (route.access !== 'public' && !tokens.has(token))
          throw new MockError(401, 'Mock 登录已过期')
        const body = await requestBody(request)
        const data = route.handle({
          params,
          query: url.searchParams,
          body,
          token,
        })
        if (data instanceof MockFile) {
          response.writeHead(200, {
            'Content-Type': data.contentType,
            'Cache-Control': 'no-store',
            ...(data.filename
              ? { 'Content-Disposition': `attachment; filename="${data.filename}"` }
              : {}),
          })
          response.end(data.content)
        }
        else {
          send(response, 200, { code: 200, message: 'OK', data: data ?? null })
        }
      }
      catch (error) {
        if (error instanceof MockError) {
          send(response, error.status, { code: error.status, message: error.message })
        }
        else {
          console.error(error)
          send(response, 500, { code: 500, message: 'Mock 处理请求失败' })
        }
      }
      return
    }
    send(response, 501, { code: 501, message: `Mock 未实现 ${request.method} ${url.pathname}` })
  }
}

export function mockApiPlugin() {
  return {
    name: 'flow-dev-mock',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use(createMockMiddleware())
    },
  }
}
