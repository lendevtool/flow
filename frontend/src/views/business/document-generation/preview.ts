function inlineScript(nonce: string, source: string) {
  return `<script nonce="${nonce}">${source.replace(/<\/script/gi, '<\\/script').replace(/\/\/# sourceMappingURL=.*$/gm, '')}</script>`
}

export function createPreviewDocument(nonce: string, zip: string, renderer: string, loader: string) {
  const policy = [
    'default-src \'none\'',
    `script-src 'nonce-${nonce}'`,
    'style-src \'unsafe-inline\'',
    'img-src data: blob:',
    'font-src data: blob:',
    'base-uri \'none\'',
    'connect-src \'none\'',
    'form-action \'none\'',
    'frame-src \'none\'',
  ].join('; ')
  return `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${policy}"><meta name="referrer" content="no-referrer"></head><body style="margin:0"><div id="preview"></div>${inlineScript(nonce, zip)}${inlineScript(nonce, renderer)}${inlineScript(nonce, loader)}</body></html>`
}

export function connectPreview(
  frame: { postMessage: (message: string, targetOrigin: string, transfer: MessagePort[]) => void },
  bytes: ArrayBuffer,
  channel = new MessageChannel(),
  timeout = 15000,
) {
  let settle: (success: boolean) => void
  const done = new Promise<boolean>((resolve) => {
    settle = resolve
  })
  const timer = setTimeout(finish, timeout, false)
  let closed = false

  function finish(success: boolean) {
    if (closed)
      return
    closed = true
    clearTimeout(timer)
    channel.port1.close()
    settle(success)
  }

  channel.port1.onmessage = ({ data }) => finish(data === 'ready')
  channel.port1.onmessageerror = () => finish(false)
  try {
    // 不透明来源的沙箱必须使用 *；只把端口送到当前 iframe，再经专用端口传输文件。
    frame.postMessage('document-generation-preview', '*', [channel.port2])
    channel.port1.postMessage(bytes, [bytes])
  }
  catch {
    channel.port2.close()
    finish(false)
  }

  return {
    done,
    close: () => finish(false),
  }
}
