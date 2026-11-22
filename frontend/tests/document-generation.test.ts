import { describe, expect, it, vi } from 'vitest'
import { createInputEditor, createSubmissionKeys, createValidator, parseJsonObject, updateJsonField } from '@/views/business/document-generation/input'
import { connectPreview, createPreviewDocument } from '@/views/business/document-generation/preview'

describe('document generation input', () => {
  const schema = {
    type: 'object',
    required: ['report'],
    properties: {
      report: { $ref: '#/$defs/report' },
      date: { type: 'string', format: 'date' },
    },
    $defs: {
      report: {
        type: 'object',
        required: ['rows'],
        properties: {
          rows: {
            type: 'array',
            items: {
              type: ['integer', 'null'],
              minimum: 0,
            },
          },
        },
      },
    },
  }

  it('rejects non-objects and decoded duplicate keys without losing false, zero or null', () => {
    expect(() => parseJsonObject('[]')).toThrow()
    expect(() => parseJsonObject('{"unsafe":9007199254740993}')).toThrow()
    expect(parseJsonObject('{"safe":"9007199254740993"}')).toEqual({ safe: '9007199254740993' })
    expect(() => parseJsonObject('{"nested":{"name":1,"\\u006eame":2}}')).toThrow()
    expect(parseJsonObject('{"text":"","number":0,"checked":false,"item":null}'))
      .toEqual({
        text: '',
        number: 0,
        checked: false,
        item: null,
      })
  })

  it('keeps invalid JSON in the editor until it can return to the form', () => {
    const editor = createInputEditor(createValidator(schema))
    expect(editor.replace({
      report: {
        rows: [0, null],
      },
      extra: false,
    }, () => true)).toBe(true)
    expect(editor.switchTo('json')).toBe(true)
    editor.setText('{"report":{"rows":[-1]},"date":"2025-02-29"}')
    expect(editor.switchTo('form')).toBe(false)
    expect(editor.state.mode).toBe('json')
    expect(editor.state.text).toContain('[-1]')
    expect(editor.state.issues.map(issue => issue.path)).toEqual(['/report/rows/0', '/date'])
    expect(editor.state.object).toEqual({
      report: {
        rows: [0, null],
      },
      extra: false,
    })
    editor.setText('{"report":{"rows":[1,null]},"extra":false}')
    expect(editor.switchTo('form')).toBe(true)
    expect(editor.state.object).toEqual({
      report: {
        rows: [1, null],
      },
      extra: false,
    })
  })

  it('adds and removes array rows while preserving omitted, empty and extra values', () => {
    const editor = createInputEditor(createValidator(schema))
    editor.replace({
      report: { rows: [], name: '' },
      extra: false,
    }, () => true)
    const withRow = updateJsonField(editor.state.object, ['report', 'rows', 0], 0)
    const withNull = updateJsonField(withRow, ['report', 'rows', 1], null)
    editor.setObject(updateJsonField(withNull, ['report', 'rows', 0], undefined))
    expect(editor.switchTo('json')).toBe(true)
    expect(JSON.parse(editor.state.text)).toEqual({
      report: {
        rows: [null],
        name: '',
      },
      extra: false,
    })
  })

  it('overwrites dirty input only after an explicit confirmation', () => {
    const editor = createInputEditor(createValidator(schema))
    editor.setObject({
      report: {
        rows: [1],
      },
    })
    expect(editor.replace({
      report: {
        rows: [2],
      },
    }, () => false)).toBe(false)
    expect(editor.state.object).toEqual({
      report: {
        rows: [1],
      },
    })
    expect(editor.replace({
      report: {
        rows: [2],
      },
    }, () => true)).toBe(true)
    expect(editor.state.object).toEqual({
      report: {
        rows: [2],
      },
    })
    expect(editor.state.dirty).toBe(false)
  })

  it('keeps an uncertain request key for the same object, including reordering, until a result is known', () => {
    const createKey = vi.fn().mockReturnValueOnce('first').mockReturnValueOnce('second').mockReturnValueOnce('third')
    const keys = createSubmissionKeys(createKey)
    expect(keys.get('42', {
      b: false,
      a: [1, 2],
    })).toBe('first')
    expect(keys.get('42', {
      a: [1, 2],
      b: false,
    })).toBe('first')
    expect(keys.get('42', {
      a: [2, 1],
      b: false,
    })).toBe('second')
    keys.finish('42', {
      a: [2, 1],
      b: false,
    })
    expect(keys.get('42', {
      a: [1, 2],
      b: false,
    })).toBe('first')
    expect(keys.get('42', {
      a: [2, 1],
      b: false,
    })).toBe('third')
  })
})

describe('word preview', () => {
  it('uses script nonces, disallows network access, and loads inline document images', () => {
    const html = createPreviewDocument('aNonce', 'zip();', 'docx();', 'loader();')
    expect(html).toContain('script-src \'nonce-aNonce\'')
    expect(html).toContain('connect-src \'none\'')
    expect(html).toContain('frame-src \'none\'')
    expect(html).toContain('img-src data: blob:')
    expect(html).toContain('<script nonce="aNonce">loader();</script>')
    expect(html).not.toContain('allow-same-origin')
  })

  it('reports renderer failure and closes the preview channel', async () => {
    const channel = new MessageChannel()
    const frame = {
      postMessage: (_message: unknown, _origin: string, ports: MessagePort[]) => {
        const port = ports[0]!
        port.onmessage = ({ data }) => {
          expect(new Uint8Array(data)).toEqual(new Uint8Array([1, 2, 3]))
          port.postMessage('failed')
          port.close()
        }
      },
    }
    const close = vi.spyOn(channel.port1, 'close')
    const result = connectPreview(frame, new Uint8Array([1, 2, 3]).buffer, channel)
    await expect(result.done).resolves.toBe(false)
    expect(close).toHaveBeenCalledOnce()
  })

  it('stops waiting when the preview closes before the renderer responds', async () => {
    const channel = new MessageChannel()
    const close = vi.spyOn(channel.port1, 'close')
    const result = connectPreview({
      postMessage: () => channel.port2.close(),
    }, new ArrayBuffer(0), channel)
    result.close()
    await expect(result.done).resolves.toBe(false)
    expect(close).toHaveBeenCalledOnce()
  })
})
