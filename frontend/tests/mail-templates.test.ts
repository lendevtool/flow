import { describe, expect, it } from 'vitest'
import { discoverVariables } from '@/views/pms/mail/templateVariables'

describe('mail template variables', () => {
  it('lists each valid variable once in subject and body order', () => {
    expect(discoverVariables(
      '{{task.name}} · {{recipient.name}}',
      '[打开]({{task.url}}) {{task.name}} {{bad-name}} {{broken..path}}',
    )).toEqual(['task.name', 'recipient.name', 'task.url'])
  })
})
