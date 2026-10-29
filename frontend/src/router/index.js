import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '@/api/http'

const routes = [
  { path: '/signin', component: () => import('@/views/SignIn.vue'), meta: { title: '登入', public: true } },
  { path: '/', component: () => import('@/views/Dashboard.vue'), meta: { title: '儀表板' } },
  { path: '/chart', component: () => import('@/views/Chart.vue'), meta: { title: '即時看盤' } },
  { path: '/strategy', component: () => import('@/views/Strategy.vue'), meta: { title: '策略管理' } },
  { path: '/backtest', component: () => import('@/views/Backtest.vue'), meta: { title: '回測結果' } },
  { path: '/live', component: () => import('@/views/Live.vue'), meta: { title: '實盤監控' } },
  { path: '/account', component: () => import('@/views/Account.vue'), meta: { title: '帳戶與部位' } },
  { path: '/orders', component: () => import('@/views/Orders.vue'), meta: { title: '訂單紀錄' } },
  { path: '/risk', component: () => import('@/views/Risk.vue'), meta: { title: '風控設定' } },
  // 幣安金鑰 / 環境設定：會換金鑰、切 mainnet、關 dry-run，必須登入
  { path: '/login', component: () => import('@/views/Login.vue'), meta: { title: '連線設定' } }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  if (to.meta.public) return true
  if (getToken()) return true
  return { path: '/signin', query: { redirect: to.fullPath } }
})

export default router
