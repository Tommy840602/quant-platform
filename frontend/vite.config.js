import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': '/src' } },
  build: {
    // 把大依賴拆成獨立 chunk：改業務碼時 echarts / element-plus 的 chunk 不變、瀏覽器快取有效，首頁也只載需要的
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          element: ['element-plus', '@element-plus/icons-vue'],
          echarts: ['echarts']
        }
      }
    },
    chunkSizeWarningLimit: 1200   // echarts 單檔本來就 ~1 MB，拆出後不再對它警告
  },
  server: {
    port: 5173,
    proxy: {
      // 走 API Gateway
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws':  { target: 'ws://localhost:8080', ws: true },
      // 直連 Python 研究服務（見架構文件 §8）
      '/research': {
        target: 'http://localhost:8000',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/research/, '')
      }
    }
  }
})
