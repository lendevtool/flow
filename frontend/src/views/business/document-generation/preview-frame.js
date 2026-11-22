/* global docx */
window.addEventListener('message', function open(event) {
  if (event.source !== window.parent || event.data !== 'document-generation-preview' || event.ports.length !== 1)
    return
  window.removeEventListener('message', open)
  const [port] = event.ports
  const container = document.querySelector('#preview')
  port.onmessage = async ({ data }) => {
    try {
      if (!(data instanceof ArrayBuffer))
        throw new TypeError('Expected DOCX bytes')
      // Base64 资源随 iframe 回收，不在页面之间共享对象 URL。
      await docx.renderAsync(data, container, container, { renderAltChunks: false, useBase64URL: true })
      for (const link of container.querySelectorAll('a')) {
        link.removeAttribute('href')
        link.removeAttributeNS('http://www.w3.org/1999/xlink', 'href')
      }
      port.postMessage('ready')
    }
    catch {
      container.replaceChildren()
      port.postMessage('failed')
    }
    finally {
      port.close()
    }
  }
  port.onmessageerror = () => {
    port.postMessage('failed')
    port.close()
  }
}, false)

document.addEventListener('click', (event) => {
  if (event.target instanceof Element && event.target.closest('a'))
    event.preventDefault()
}, true)
