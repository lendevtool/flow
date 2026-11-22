import { describe, expect, it } from 'vitest'
import { applyDefaults, fieldDefinitions, fieldDraft, itemPayload, latestRequest, validateConfig } from '../src/views/pms/param/fields'

describe('parameter fields', () => {
  it('keeps fixed storage keys canonical even when metadata contains another key', () => {
    const config = {
      fixedFields: {
        value1: {
          key: 'name',
          label: '别名',
          type: 'text',
          defaultValue: 'example',
        },
      },
    }
    expect(fieldDefinitions(config)[0].key).toBe('value1')
    expect(applyDefaults(config).name).toBe('')
    expect(applyDefaults(config).value1).toBe('example')
  })

  it('locks persisted field identities without locking new fields matching an existing prefix', () => {
    const existing = fieldDraft({ key: 'user', label: '用户', type: 'text' }, true)
    const added = fieldDraft({ key: '', label: '', type: 'text' })
    added.key = 'user'
    expect(added.keyLocked).toBe(false)
    added.key = 'userName'
    expect(added.keyLocked).toBe(false)
    expect(existing.keyLocked).toBe(true)
  })
  const config = {
    fixedFields: {
      value1: {
        label: '地址',
        type: 'text',
        defaultValue: '/home',
        showInTable: true,
      },
    },
    extraFields: [
      {
        key: 'count',
        label: '数量',
        type: 'number',
        defaultValue: 0,
      },
      {
        key: 'secure',
        label: '需要登录',
        type: 'boolean',
        defaultValue: false,
      },
    ],
  }

  it('keeps fixed column storage keys and uses configured labels', () => {
    const fields = fieldDefinitions(config)
    expect(fields).toHaveLength(11)
    expect(fields[0]).toMatchObject({ key: 'value1', label: '地址', storage: 'fixed' })
    expect(fields[9]).toMatchObject({ key: 'count', storage: 'extra' })
  })

  it('applies falsy defaults without modifying the schema', () => {
    const item = applyDefaults(config)
    expect(item).toMatchObject({
      value1: '/home',
      extra: { count: 0, secure: false },
    })
    item.extra.count = 5
    expect(config.extraFields[0].defaultValue).toBe(0)
  })

  it('serializes fixed typed values as strings and preserves unconfigured JSON values', () => {
    const result = itemPayload({
      id: 22,
      code: 'home',
      name: '首页',
      enable: true,
      sort: 0,
      createdBy: 999,
      updatedBy: 888,
      value1: 0,
      value2: false,
      extra: {
        count: 0,
        secure: false,
        nested: { keep: true },
      },
    })
    expect(result.value1).toBe('0')
    expect(result.value2).toBe('false')
    expect(result.extra).toEqual({
      count: 0,
      secure: false,
      nested: { keep: true },
    })
    expect(result).not.toHaveProperty('createdBy')
    expect(result).not.toHaveProperty('updatedBy')
    expect(result).not.toHaveProperty('id')
  })

  it('rejects duplicate JSON keys, invalid keys and empty select options', () => {
    expect(() => validateConfig({
      extraFields: [
        { key: 'a', label: 'a', type: 'text' },
        { key: 'a', label: 'b', type: 'text' },
      ],
    })).toThrow()
    expect(() => validateConfig({
      extraFields: [
        { key: 'constructor', label: 'a', type: 'text' },
      ],
    })).toThrow()
    expect(() => validateConfig({
      fixedFields: {
        value1: { label: '模式', type: 'select', options: [] },
      },
    })).toThrow()
    expect(() => validateConfig(config)).not.toThrow()
  })
})

it('ignores stale successes and errors after switching a parameter group', async () => {
  const latest = latestRequest()
  let finish
  const old = latest.run(() => new Promise(resolve => finish = resolve))
  const current = await latest.run(async () => ({ groupId: 2 }))
  finish({ groupId: 1 })
  expect(await old).toEqual({ current: false })
  expect(current).toEqual({
    current: true,
    data: { groupId: 2 },
  })
  let fail
  const previous = latest.run(() => new Promise((resolve, reject) => fail = reject))
  latest.invalidate()
  fail(new Error('old request'))
  expect(await previous).toEqual({ current: false })
})
