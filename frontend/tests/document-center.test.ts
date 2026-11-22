import type { AxiosAdapter, AxiosResponse } from 'axios'
import { AxiosError } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { request } from '@/utils/http'
import generationApi, { downloadGeneratedDocument } from '@/views/business/document-generation/api'
import api from '@/views/business/documents/api'
import { buildDocumentQuery, documentFileAccess, documentSelectOptions } from '@/views/business/documents/model'

vi.mock('@/store', () => ({
  useAuthStore: () => ({ accessToken: 'test-token' }),
}))

const defaultAdapter = request.defaults.adapter

afterEach(() => {
  request.defaults.adapter = defaultAdapter
  vi.unstubAllGlobals()
})

describe('document center filters and editing', () => {
  it('uses the six flat query parameters and omits cleared filters', () => {
    expect(buildDocumentQuery({
      keyword: '  会议纪要  ',
      departmentId: null,
      productId: 3,
      dimensions: {
        1: '  交付  ',
        2: '15',
        3: '',
        4: null,
        6: '华东',
      },
    }, 2, 20)).toEqual({
      pageNo: 2,
      pageSize: 20,
      keyword: '会议纪要',
      productId: 3,
      dimension1Value: '交付',
      dimension2Value: '15',
      dimension6Value: '华东',
    })
  })

  it('permits filtering on inactive IDs and preserves an existing inactive selection during editing', () => {
    const options = [
      { id: 12, name: '其他', enabled: true },
      { id: 23, name: '历史项', enabled: false },
      { id: 34, name: '已停用项', enabled: false },
    ]
    expect(documentSelectOptions(options).map(option => option.value)).toEqual([12, 23, 34])
    expect(documentSelectOptions(options, null).map(option => option.value)).toEqual([12])
    expect(documentSelectOptions(options, 23).map(option => option.value)).toEqual([12, 23])
  })
})

function respond(adapter: AxiosAdapter) {
  request.defaults.adapter = adapter
}

describe('document center transfers', () => {
  it('sends the initial file with an application/json metadata part and the bearer token', async () => {
    const file = new File(['text'], 'note.txt', { type: 'text/plain' })
    respond(async (config) => {
      expect(config.url).toBe('/documents')
      expect(config.method).toBe('post')
      expect(config.headers.get('Authorization')).toBe('Bearer test-token')
      expect(config.data).toBeInstanceOf(FormData)
      const metadata = config.data.get('metadata')
      expect(metadata).toBeInstanceOf(Blob)
      expect(metadata.type).toBe('application/json')
      expect(JSON.parse(await metadata.text())).toEqual({
        name: '会议纪要',
        departmentId: null,
        productId: 3,
        dimensions: { 1: '华东' },
      })
      expect(config.data.get('file')).toBe(file)
      return {
        config,
        data: {
          code: 0,
          data: { id: 8 },
        },
        status: 200,
        statusText: 'OK',
        headers: { 'content-type': 'application/json' },
      }
    })

    await expect(api.create({
      name: '会议纪要',
      departmentId: null,
      productId: 3,
      dimensions: { 1: '华东' },
    }, file)).resolves.toEqual({
      code: 0,
      data: { id: 8 },
    })
  })

  it('downloads JSON attachments with the versioned UTF-8 filename supplied by the server', async () => {
    const body = new Blob(['{"code":42}'], { type: 'application/json' })
    respond(async (config) => {
      expect(config.url).toBe('/documents/8/download')
      expect(config.params).toEqual({ versionId: 17 })
      expect(config.responseType).toBe('blob')
      return {
        config,
        data: body,
        status: 200,
        statusText: 'OK',
        headers: {
          'content-type': 'application/json',
          'content-disposition': `attachment; filename="note.json"; filename*=UTF-8''%E7%BA%AA%E8%A6%81_20261112V2.json`,
        },
      }
    })

    await expect(api.download(8, 17)).resolves.toEqual({
      file: body,
      name: '纪要_20261112V2.json',
    })
  })

  it('uses the JSON error envelope when a binary request is denied', async () => {
    vi.stubGlobal('window', {
      $message: {
        error: vi.fn(),
      },
    })
    respond(async (config) => {
      const response: AxiosResponse = {
        config,
        data: new Blob(['{"code":41001,"message":"版本已删除"}'], { type: 'application/json' }),
        status: 400,
        statusText: 'Bad Request',
        headers: { 'content-type': 'application/json' },
      }
      throw new AxiosError('Bad Request', undefined, config, undefined, response)
    })

    await expect(api.download(8)).rejects.toMatchObject({ code: 41001, message: '版本已删除' })
  })

  it.each([
    [413, 'The gateway refused the body', '文件太大，请选择较小的文件后重新上传'],
    [502, 'Bad gateway upstream', '暂时无法上传文件，请稍后重试'],
    [
      400,
      { code: 400, message: '请选择要上传的文件' },
      '请选择要上传的文件',
    ],
    [
      503,
      { code: 503, message: 'Internal storage path' },
      '暂时无法上传文件，请稍后重试',
    ],
  ])('shows an actionable upload error for HTTP %i', async (status, data, message) => {
    const warning = vi.fn()
    vi.stubGlobal('window', {
      $message: { warning },
    })
    respond(async (config) => {
      throw new AxiosError('Raw transport failure', undefined, config, undefined, {
        config,
        data,
        status,
        statusText: 'Request failed',
        headers: {},
      })
    })

    await expect(api.addVersion(8, new File(['text'], 'note.txt'))).rejects.toMatchObject({ code: status })
    expect(warning).toHaveBeenCalledExactlyOnceWith(message)
  })
})

describe('document generation sources in document center', () => {
  it('uses the generation menu and management permission independently', () => {
    expect(documentFileAccess('USER', false, [])).toEqual({ types: [], canDownloadGenerations: false })
    expect(documentFileAccess('USER', true, [])).toEqual({
      types: ['GENERATION'],
      canDownloadGenerations: false,
    })
    expect(documentFileAccess('USER', true, ['DocumentGenerationReadFile'])).toEqual({
      types: ['GENERATION'],
      canDownloadGenerations: true,
    })
    expect(documentFileAccess('USER', false, ['DocumentGenerationTemplateManage', 'DocumentGenerationReadFile'])).toEqual({
      types: ['TEMPLATE_VERSION'],
      canDownloadGenerations: false,
    })
    expect(documentFileAccess('SUPER_ADMIN', false, [])).toEqual({
      types: ['TEMPLATE_VERSION', 'GENERATION'],
      canDownloadGenerations: true,
    })
  })

  it('queries the requested origin without sending upload-only filters', async () => {
    respond(async (config) => {
      expect(config.url).toBe('/document-generation/files')
      expect(config.params).toEqual({
        type: 'GENERATION',
        keyword: '月报',
        pageNo: 2,
        pageSize: 20,
      })
      expect(config.headers.get('Authorization')).toBe('Bearer test-token')
      return {
        config,
        data: {
          code: 0,
          data: { records: [], total: 0 },
        },
        status: 200,
        statusText: 'OK',
        headers: { 'content-type': 'application/json' },
      }
    })
    await expect(generationApi.files({
      type: 'GENERATION',
      keyword: '月报',
      pageNo: 2,
      pageSize: 20,
    }))
      .resolves
      .toEqual({
        code: 0,
        data: { records: [], total: 0 },
      })
  })

  it('downloads the exact selected template version or owned generation through its business endpoint', async () => {
    const bytes = new Blob(['word'])
    const paths: string[] = []
    respond(async (config) => {
      paths.push(config.url ?? '')
      expect(config.responseType).toBe('blob')
      return {
        config,
        data: bytes,
        status: 200,
        statusText: 'OK',
        headers: { 'content-disposition': 'attachment; filename="report.docx"' },
      }
    })
    const file = {
      templateId: '9007199254740993',
      templateVersionId: '9007199254740994',
      templateName: '月报',
      versionNo: 4,
      originalFileName: 'report.docx',
      sftpPath: 'documents/202611/1/2026cace-69e3-4f2e-9e8c-b6a341eaf493',
      sizeBytes: 4,
      createdAt: '2026-11-22T03:00:00Z',
    }
    await expect(downloadGeneratedDocument({
      ...file,
      type: 'TEMPLATE_VERSION',
      status: 'DRAFT',
      generationId: null,
    }))
      .resolves
      .toEqual({ file: bytes, name: 'report.docx' })
    await expect(downloadGeneratedDocument({
      ...file,
      type: 'GENERATION',
      status: 'SUCCEEDED',
      generationId: '9007199254740995',
    }))
      .resolves
      .toEqual({ file: bytes, name: 'report.docx' })
    expect(paths).toEqual([
      '/document-generation/admin/templates/9007199254740993/versions/9007199254740994/file',
      '/document-generation/generations/9007199254740995/file',
    ])
  })
})
