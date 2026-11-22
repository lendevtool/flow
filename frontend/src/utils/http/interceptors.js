import { isCancel } from 'axios'
import { language } from '@/i18n'
import { useAuthStore } from '@/store'
import { resolveResError } from './helpers'

export function setupInterceptors(axiosInstance) {
  const SUCCESS_CODES = [0, 200]
  async function resResolve(response) {
    const { status, config, statusText, headers } = response
    // 附件自身可以是 JSON，使用附件响应头区分文件和业务响应。
    if (config.keepAttachmentResponse && /^attachment(?:;|$)/i.test(headers['content-disposition'] ?? ''))
      return response
    const data = config.keepAttachmentResponse
      ? await readAttachmentError(response)
      : response.data
    if (headers['content-type']?.includes('json')) {
      if (SUCCESS_CODES.includes(data?.code)) {
        return Promise.resolve(data)
      }
      const code = data?.code ?? status

      const needTip = config?.needTip !== false

      // 根据code处理对应的操作，并返回处理后的message
      const message = resolveResError(code, errorMessage(code, data, headers, statusText), needTip)

      return Promise.reject({ code, message, error: data ?? response })
    }
    return Promise.resolve(data ?? response)
  }

  axiosInstance.interceptors.request.use(reqResolve, reqReject)
  axiosInstance.interceptors.response.use(resResolve, resReject)
}

async function readAttachmentError({ data, headers }) {
  if (data instanceof Blob && headers['content-type']?.includes('json')) {
    try {
      return JSON.parse(await data.text())
    }
    catch {
      // 解析失败时沿用 HTTP 状态和通用错误提示。
    }
  }
  return data
}

function errorMessage(code, data, headers, fallback) {
  if (code === 500) {
    // 仅展示应用主动返回的错误文案，代理或框架生成的 500 使用通用提示。
    return headers['content-type']?.includes('json') && data?.code === 500
      ? data.message
      : undefined
  }
  return data?.message ?? fallback
}

function reqResolve(config) {
  config.headers.set('Accept-Language', language.value)
  // 处理不需要token的请求
  if (config.needToken === false) {
    return config
  }

  const { accessToken } = useAuthStore()
  if (accessToken) {
    // token: Bearer + xxx
    config.headers.Authorization = `Bearer ${accessToken}`
  }

  return config
}

function reqReject(error) {
  return Promise.reject(error)
}

async function resReject(error) {
  if (isCancel(error))
    return Promise.reject(error)

  if (!error || !error.response) {
    const code = error?.code
    /** 根据code处理对应的操作，并返回处理后的message */
    const message = resolveResError(code, error?.message, error?.config?.needTip !== false)
    return Promise.reject({ code, message, error })
  }

  const { status, config, headers } = error.response
  const data = config.keepAttachmentResponse
    ? await readAttachmentError(error.response)
    : error.response.data
  const code = data?.code ?? status

  const needTip = config?.needTip !== false
  const message = resolveResError(code, errorMessage(code, data, headers, error.message), needTip)
  return Promise.reject({ code, message, error: error.response?.data || error.response })
}
