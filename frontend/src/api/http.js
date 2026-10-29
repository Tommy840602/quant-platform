import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 15000 })

// JWT：記憶體為主，sessionStorage 作重新整理後的還原（關閉分頁即清除；不用 localStorage）
const KEY = 'qp-token'
let accessToken = null
try { accessToken = sessionStorage.getItem(KEY) } catch {}
export function setToken(t) {
  accessToken = t
  try { if (t) sessionStorage.setItem(KEY, t); else sessionStorage.removeItem(KEY) } catch {}
}
export function getToken() { return accessToken }

http.interceptors.request.use((cfg) => {
  if (accessToken) cfg.headers.Authorization = `Bearer ${accessToken}`
  return cfg
})
http.interceptors.response.use(
  (res) => res.data,
  (err) => {
    if (err.response?.status === 401 && !String(err.config?.url || '').includes('/auth/login')) {
      setToken(null)
      const here = window.location.pathname + window.location.search
      if (!here.startsWith('/signin')) window.location.assign('/signin?redirect=' + encodeURIComponent(here))
    }
    return Promise.reject(err)
  }
)
export default http
