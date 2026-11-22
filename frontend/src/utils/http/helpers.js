import { t } from '@/i18n'
import { useAuthStore } from '@/store'

let isConfirming = false

function handleAuthExpired(content, needTip) {
  if (isConfirming || !needTip)
    return
  isConfirming = true
  $dialog.confirm({
    title: t('提示'),
    type: 'info',
    content,
    confirm() {
      useAuthStore().logout()
      window.$message?.success(t('已退出登录'))
      isConfirming = false
    },
    cancel() {
      isConfirming = false
    },
  })
  return false
}

export function resolveResError(code, message, needTip = true) {
  switch (code) {
    case 401:
      return handleAuthExpired(t('登录已过期，是否重新登录？'), needTip)
    case 11007:
    case 11008:
      return handleAuthExpired(t('{0}，是否重新登录？', message), needTip)
    case 'ERR_NETWORK':
      message = t('网络连接失败，请检查网络后重试')
      break
    case 'ECONNABORTED':
    case 'ETIMEDOUT':
      message = t('请求超时，请稍后重试')
      break
    case 403:
      message = t('请求被拒绝')
      break
    case 404:
      message = t('请求资源或接口不存在')
      break
    case 500:
      message = typeof message === 'string' && message.trim()
        ? message
        : t('服务器发生异常')
      break
    default:
      message = message ?? t('【{0}】: 未知异常!', code)
      break
  }
  needTip && window.$message?.error(message)
  return message
}
