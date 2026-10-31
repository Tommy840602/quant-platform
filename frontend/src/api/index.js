import axios from 'axios'
import http from './http'

export const authApi = {
  login: (body) => http.post('/auth/login', body),
  me: () => http.get('/auth/me'),
  changePassword: (oldPassword, newPassword) => http.post('/auth/password', { oldPassword, newPassword }),
  register: (username, password, roles = 'TRADER') => http.post('/auth/register', { username, password, roles })
}
export const marketApi = {
  klines: (symbol, interval = '1m', limit = 500) =>
    http.get('/market/klines', { params: { symbol, interval, limit } })
}
export const strategyApi = {
  list: () => http.get('/strategy/strategies'),
  start: (id) => http.post(`/strategy/strategies/${id}/start`),
  stop: (id) => http.post(`/strategy/strategies/${id}/stop`),
  create: (body) => http.post('/strategy/strategies', body),
  params: (id, body) => http.post(`/strategy/strategies/${id}/params`, body),
  allocation: (id, weight) => http.post(`/strategy/strategies/${id}/allocation`, null, { params: { weight } }),
  getOrderNotional: () => http.get('/strategy/strategies/order-notional'),
  setOrderNotional: (value) => http.post('/strategy/strategies/order-notional', null, { params: { value } })
}
export const orderApi = {
  submit: (intent) => http.post('/order/orders', intent),
  query: (id, symbol, market='SPOT') => http.get(`/order/orders/${id}`, { params: { symbol, market } }),
  cancel: (id, symbol, market='SPOT') => http.delete(`/order/orders/${id}`, { params: { symbol, market } }),
  recent: (limit = 50) => http.get('/order/orders', { params: { limit } }),
  kpi: () => http.get('/order/orders/kpi'),
  attribution: () => http.get('/order/orders/attribution'),
  condCreate: (body) => http.post('/order/orders/conditional', body),
  condList: () => http.get('/order/orders/conditional'),
  condCancel: (id) => http.delete(`/order/orders/conditional/${id}`),
  riskConfig: () => http.get('/order/orders/risk-config'),
  setRiskConfig: (body) => http.post('/order/orders/risk-config', body)
}
export const riskApi = {
  getKill: () => http.get('/risk/kill-switch'),
  setKill: (on, reason = 'manual') => http.post('/risk/kill-switch', null, { params: { on, reason } })
}
export const accountApi = {
  balance: () => http.get('/account/balance'),
  positions: () => http.get('/account/positions'),
  getConfig: () => http.get('/account/config'),
  summaryNow: () => http.post('/account/summary/now'),
  setConfig: (body) => http.post('/account/config', body)
}
// 下單連線設定（env/dry-run/金鑰）
export const tradeConfigApi = {
  get: () => http.get('/order/config'),
  set: (body) => http.post('/order/config', body)
}
// Java 事件驅動回測（走 gateway）
export const backtestApi = {
  run: (payload) => http.post('/backtest/run', payload),
  optimize: (payload) => http.post('/backtest/optimize', payload),
  portfolio: (payload) => http.post('/backtest/portfolio', payload)
}

// Python 向量化回測走獨立 proxy（/research -> :8000）
const research = axios.create({ baseURL: '/research', timeout: 20000 })
export const researchApi = {
  backtest: (payload) => research.post('/backtest/run', payload).then(r => r.data)
}

export const notifyApi = {
  test: (text) => http.post('/notify/test', { text })
}
