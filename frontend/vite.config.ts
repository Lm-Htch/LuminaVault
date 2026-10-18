import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig(() => {
  const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env
  const proxyTarget = env?.LUMINA_PROXY_TARGET ?? 'http://localhost:8080'
  return {
    plugins: [react(), tailwindcss()],
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
        },
        // 尾斜杠避免误匹配 /folders 等以 /f 开头的页面路由
        '/f/': {
          target: proxyTarget,
          changeOrigin: true,
        },
      },
    },
  }
})
