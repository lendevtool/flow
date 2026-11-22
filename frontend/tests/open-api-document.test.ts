import { describe, expect, it } from 'vitest'
import { documentFile, openApiMarkdown } from '../src/views/pms/open-api/documentDownload'

const document = {
  openapi: '3.1.0',
  info: { title: 'Flow Open API', version: 'v1', description: '接入文档' },
  servers: [
    { url: 'https://example.test', description: '服务地址' },
  ],
  security: [
    { appBearer: [] },
  ],
  paths: {
    '/oauth2/token': {
      post: {
        summary: '获取令牌',
        requestBody: {
          content: {
            'application/x-www-form-urlencoded': {
              schema: { type: 'object' },
            },
          },
        },
        responses: {
          200: { description: 'OK' },
        },
      },
    },
    '/open/v1/items': {
      description: '目录查询',
      parameters: [
        {
          name: 'pageSize',
          in: 'query',
          schema: { type: 'integer' },
          description: '共享参数',
        },
      ],
      get: {
        'summary': '查询字段目录',
        'x-required-scope': 'items.read',
        'security': [
          { appBearer: [] },
        ],
        'parameters': [
          {
            name: 'keyword',
            in: 'query',
            required: true,
            schema: { type: 'string' },
            description: '关键字 | 中文\n下一行',
          },
          {
            name: 'pageSize',
            in: 'query',
            schema: { type: 'integer' },
            description: '覆盖共享参数',
          },
        ],
        'responses': {
          200: {
            description: 'OK',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/Item' },
              },
            },
          },
          403: {
            description: 'Insufficient interface scope',
            headers: {
              'WWW-Authenticate': {
                schema: { type: 'string' },
              },
            },
          },
        },
      },
    },
  },
  components: {
    securitySchemes: {
      appBearer: { type: 'http', scheme: 'bearer' },
    },
    schemas: {
      Item: {
        type: 'object',
        properties: {
          name: {
            type: ['string', 'null'],
          },
        },
      },
    },
  },
}

describe('openAPI document downloads', () => {
  it('keeps the JSON attachment unchanged', async () => {
    const source = new Blob([
      JSON.stringify(document),
    ], { type: 'application/json' })
    const file = await documentFile(source, 'json')
    expect(file.name).toBe('flow-open-api-v1.json')
    expect(file.blob).toBe(source)
  })

  it('exports UTF-8 Markdown with authentication, scopes, request and response contracts and referenced models', async () => {
    const file = await documentFile(new Blob([
      JSON.stringify(document),
    ]), 'markdown')
    const markdown = await file.blob.text()
    expect(file.name).toBe('flow-open-api-v1.md')
    expect(file.blob.type).toBe('text/markdown;charset=utf-8')
    for (const content of [
      '# Flow Open API',
      '接入文档',
      'https://example.test',
      '## 鉴权方式',
      '"scheme": "bearer"',
      '### POST /oauth2/token',
      'application/x-www-form-urlencoded',
      '### GET /open/v1/items',
      '所需 Scope：items.read',
      '"requestBody"',
      '"responses"',
      'WWW-Authenticate',
      '"403"',
      '#/components/schemas/Item',
      '## 组件定义',
      '"properties"',
      '"null"',
      '目录查询',
    ]) {
      expect(markdown).toContain(content)
    }
  })

  it('honors operation parameter overrides and escapes table delimiters and line breaks', () => {
    const markdown = openApiMarkdown(document)
    expect(markdown).toContain('| keyword | query | 是 | string | 关键字 \\| 中文 下一行 |')
    expect(markdown).toContain('| pageSize | query | 否 | integer | 覆盖共享参数 |')
    expect(markdown).not.toContain('| pageSize | query | 否 | integer | 共享参数 |')
    expect(markdown.match(/\| pageSize \|/g)).toHaveLength(1)
  })

  it('keeps user text from injecting HTML, headings or closing JSON code fences', () => {
    const markdown = openApiMarkdown({
      ...document,
      info: { title: 'Title\n# Fake <script>alert(1)</script>', description: '[link](javascript:alert(1))' },
      components: {
        schemas: {
          Example: { example: '````\n<script>alert(1)</script>' },
        },
      },
    })
    expect(markdown).toContain('Title # Fake \\<script\\>')
    expect(markdown).not.toContain('\n# Fake')
    expect(markdown).toContain('\\[link\\]')
    expect(markdown).toContain('`````json')
  })

  it.each([
    null,
    [],
    {},
    { openapi: '3.1.0' },
    { openapi: '3.1.0', paths: [] },
  ])('rejects invalid contracts: %j', (value) => {
    expect(() => openApiMarkdown(value)).toThrow('Invalid OpenAPI document')
  })

  it('rejects an unreadable attachment so the download action can display an error', async () => {
    await expect(documentFile(new Blob(['<html>Error</html>']), 'markdown')).rejects.toThrow()
  })
})
