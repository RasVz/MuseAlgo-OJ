import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// 反向代理目标：本地直接跑时指向 http://localhost:8080；
// 容器内跑时由 compose 注入 VITE_PROXY_TARGET=http://backend:8080（走服务名）。
const proxyTarget = process.env.VITE_PROXY_TARGET || 'http://localhost:8080'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入：模板组件 + v-loading 等指令 + 对应样式
    AutoImport({
      resolvers: [ElementPlusResolver()],
      dts: false
    }),
    Components({
      resolvers: [ElementPlusResolver({ importStyle: 'css', directives: true })],
      dts: false
    })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  build: {
    rollupOptions: {
      output: {
        // 手动分包：仅对无 tree-shake 顾虑的大依赖拆分。
        // 注意：element-plus 因按需引入（unplugin）不可在此强制分包，
        // manualChunks 归并会破坏其 tree-shaking，导致全量打包。
        manualChunks(id) {
          if (id.includes('node_modules/echarts')) return 'echarts'
          if (id.includes('node_modules/monaco-editor') || id.includes('node_modules/@guolao')) {
            return 'monaco'
          }
          if (id.includes('node_modules/mathjax')) return 'mathjax'
          if (id.includes('node_modules/highlight.js')) return 'highlight'
        }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: proxyTarget, // 后端地址（宿主 localhost:8080 / 容器 backend:8080）
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '') // 去掉 /api 前缀
      },
      // 本地后端提供 Slidev 构建产物，供 iframe 和新窗口预览使用。
      '/coursewares': {
        target: proxyTarget,
        changeOrigin: true
      },
      // 未配置对象存储时后端把上传文件落在本地并返回 /uploads/... 相对地址，
      // 开发期必须一并代理，否则头像等图片在浏览器里 404（生产由 Nginx 转发）。
      '/uploads': {
        target: proxyTarget,
        changeOrigin: true
      }
    }
  }
})
