const variablePath = String.raw`[a-z]\w*(?:\.[a-z]\w*)*`
const variableName = new RegExp(`^${variablePath}$`, 'i')
const placeholder = new RegExp(`\\{\\{[ \\t]*(${variablePath})[ \\t]*\\}\\}`, 'gi')

export const recipientVariables = ['recipient.email', 'recipient.name']

export function isVariableName(value: string): boolean {
  return value.length <= 100 && variableName.test(value)
}

export function discoverVariables(...templates: string[]): string[] {
  return [
    ...new Set(templates.flatMap(template => Array.from(template.matchAll(placeholder), match => match[1]))),
  ]
}
