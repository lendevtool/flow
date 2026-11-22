/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_TITLE: string
  readonly VITE_USE_HASH: string
  readonly VITE_PUBLIC_PATH: string
  readonly VITE_AXIOS_BASE_URL: string
  readonly VITE_PROXY_TARGET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module 'isme:icons' {
  const icons: string[]
  export default icons
}

declare module 'isme:page-pathes' {
  const paths: string[]
  export default paths
}

interface Window {
  $message: Pick<import('naive-ui').MessageApi, 'success' | 'warning' | 'error'>
  $dialog: Pick<import('naive-ui').DialogApi, 'warning'>
}
