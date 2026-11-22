interface ExternalTarget { kind: 'web' | 'contact', url: string }

function hasControlCharacter(value: string): boolean {
  return [...value].some((character) => {
    const code = character.charCodeAt(0)
    return code < 0x20 || code === 0x7F
  })
}

/** 站内导航只接受从单个斜杠开始的路径。 */
export function internalNavigationPath(value: unknown): string | undefined {
  if (typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')
    && !value.includes('\\') && !hasControlCharacter(value)) {
    return value
  }
}

/** 识别可打开的绝对 URL。仅网页链接可以用于内嵌。 */
export function externalNavigationTarget(value: unknown): ExternalTarget | undefined {
  if (typeof value !== 'string' || value.includes('\\') || hasControlCharacter(value))
    return undefined

  const url = value.trim()
  try {
    const parsed = new URL(url)
    if (/^https?:\/\//i.test(url) && parsed.hostname && !parsed.username && !parsed.password)
      return { kind: 'web', url }
    if ((parsed.protocol === 'mailto:' || parsed.protocol === 'tel:') && parsed.pathname && !parsed.host)
      return { kind: 'contact', url }
  }
  catch {
    return undefined
  }
}
