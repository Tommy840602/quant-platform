import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需載入：只打包頁面真的用到的元件（樣式仍整包引入，見 main.js）
    Components({ resolvers: [ElementPlusResolver({ importStyle: false })], dts: false })
  ],
  resolve: { alias: { '@': '/src' } },
  build: {
    // 把大依賴拆成獨立 chunk：改業務碼時 echarts / element-plus 的 chunk 不變、瀏覽器快取有效，首頁也只載需要的
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          echarts: ['echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers']
        }
      }
    },
    chunkSizeWarningLimit: 600
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
