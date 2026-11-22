function escapeHtml(value: string) {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
}

function inline(value: string) {
  return escapeHtml(value).split(/(`[^`\n]+`)/).map((part, index) => index % 2
    ? `<code>${part.slice(1, -1)}</code>`
    : part.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>').replace(/(?<!\*)\*([^*]+)\*(?!\*)/g, '<em>$1</em>')).join('')
}

/** 处理审批编辑器支持的 Markdown 子集，所有用户输入先转义。 */
export function renderOpinion(value: string) {
  const blocks: string[] = []
  let list = ''
  for (const line of value.split(/\r?\n/)) {
    const bullet = /^(?:([-*])|\d+\.)[ \t]+(\S.*)$/.exec(line)
    const heading = /^(#{1,5})[ \t]+(\S.*)$/.exec(line)
    const nextList = bullet
      ? (bullet[1] ? 'ul' : 'ol')
      : ''
    if (list !== nextList) {
      if (list)
        blocks.push(`</${list}>`)
      if (nextList)
        blocks.push(`<${nextList}>`)
      list = nextList
    }
    if (bullet) {
      blocks.push(`<li>${inline(bullet[2])}</li>`)
    }
    else if (heading) {
      const level = heading[1].length
      blocks.push(`<h${level}>${inline(heading[2])}</h${level}>`)
    }
    else if (line.trim()) {
      blocks.push(`<p>${inline(line)}</p>`)
    }
  }
  if (list)
    blocks.push(`</${list}>`)
  return blocks.join('')
}
