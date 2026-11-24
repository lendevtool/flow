import { createRequire } from 'node:module'
import path from 'node:path'
import Vue from '@vitejs/plugin-vue'
import Unocss from 'unocss/vite'
import AutoImport from 'unplugin-auto-import/vite'
import { NaiveUiResolver } from 'unplugin-vue-components/resolvers'
import Components from 'unplugin-vue-components/vite'
import { defineConfig, loadEnv } from 'vite'
import removeNoMatch from 'vite-plugin-router-warn'
import VueDevTools from 'vite-plugin-vue-devtools'
import { pluginIcons, pluginPagePathes } from './build/plugin-isme/index.js'

const require = createRequire(import.meta.url)

export default defineConfig(async ({ command, mode, isPreview }) => {
  const viteEnv = loadEnv(mode, process.cwd())
  const { VITE_PUBLIC_PATH, VITE_PROXY_TARGET } = viteEnv
  const config = {
    base: VITE_PUBLIC_PATH || '/',
    plugins: [
      Vue(),
      VueDevTools(),
      Unocss(),
      AutoImport({
        imports: ['vue', 'vue-router'],
        dts: 'src/auto-imports.d.ts',
      }),
      Components({
        resolvers: [
          NaiveUiResolver(),
        ],
        dts: 'src/components.d.ts',
      }),
      // 自定义插件，用于生成页面文件的path，并添加到虚拟模块
      pluginPagePathes(),
      // 自定义插件，用于生成自定义icon，并添加到虚拟模块
      pluginIcons(),
      // 移除非必要的vue-router动态路由警告: No match found for location with path
      removeNoMatch(),
    ],
    resolve: {
      alias: {
        '@': path.resolve(process.cwd(), 'src'),
        '~': path.resolve(process.cwd()),
        // 沙箱预览使用 nonce 执行 UMD，包的 exports 只公开了模块入口。
        'docx-preview-umd?raw': path.join(path.dirname(require.resolve('docx-preview')), 'docx-preview.min.js?raw'),
      },
    },
    server: {
      host: '0.0.0.0',
      port: 3086,
      open: false,
      proxy: {
        '/api': {
          target: VITE_PROXY_TARGET,
          changeOrigin: true,
          rewrite: path => path.replace(/^\/api/, ''),
          secure: false,
          configure: (proxy, options) => {
            // 配置此项可在响应头中看到请求的真实地址
            proxy.on('proxyRes', (proxyRes, req) => {
              proxyRes.headers['x-real-url'] = new URL(req.url || '', options.target)?.href || ''
            })
          },
        },
        '/runapi': {
          target: 'https://runapi.co',
          changeOrigin: true,
          rewrite: path => path.replace(/^\/runapi/, '/v1'),
          secure: false,
        },
      },
    },
    optimizeDeps: {
      include: ['vue3-intro-step'],
      exclude: ['docx-preview-umd?raw'],
    },
    build: {
      chunkSizeWarningLimit: 1024, // chunk 大小警告的限制（单位kb）
    },
  }

  if (command !== 'serve' || mode !== 'mock' || isPreview)
    return config

  const { mockApiPlugin } = await import('./mock/server.js')
  return {
    ...config,
    base: '/',
    define: {
      'import.meta.env.VITE_AXIOS_BASE_URL': JSON.stringify('/api'),
      'import.meta.env.VITE_PUBLIC_PATH': JSON.stringify('/'),
    },
    plugins: [
      mockApiPlugin(),
      ...config.plugins,
    ],
    // 独立端口使演示会话和真实后端会话各自保存在浏览器中。
    server: {
      host: '127.0.0.1',
      port: 3087,
      strictPort: true,
      open: false,
    },
  }
})
