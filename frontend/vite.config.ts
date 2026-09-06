import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 开发时通过 vite proxy 调用后端 8084，构建产物直接落到 Spring Boot 的 static 目录
// 这样前端 build 后会被一起打进 app jar，运行 8084 即可访问完整站点
export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/mcp': {
        target: 'http://127.0.0.1:8084',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://127.0.0.1:8084',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
})
