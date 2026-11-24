import { runInNewContext } from 'node:vm'

// 在有时间上限的独立上下文中执行用户配置的模式。
export function matchesFieldPattern(pattern, value) {
  try {
    return runInNewContext('new RegExp(\'^(?:\' + pattern + \')$(?![\\\\s\\\\S])\', \'u\').test(value)', { pattern, value }, { timeout: 50 }) === true
  }
  catch {
    return false
  }
}
