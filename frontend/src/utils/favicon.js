import flowLogo from '@/assets/images/flow-logo.svg?raw'

export function updateFavicon(rgbColor) {
  const icon = document.querySelector('link[rel="icon"]')
  if (!icon)
    return

  const svg = flowLogo.replace('<g ', `<rect width="32" height="32" rx="4" fill="rgb(${rgbColor})" /><g `)
  icon.href = `data:image/svg+xml,${encodeURIComponent(svg)}`
}
