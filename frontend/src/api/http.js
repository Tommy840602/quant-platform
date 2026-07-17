import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 15000 })

// JWT 存記憶體（見架構文件 §11.3）
let accessToken = null
export function setToken(t) { accessToken = t }
export function getToken() { return accessToken }

http.interceptors.request.use((cfg) => {
  if (accessToken) cfg.headers.Authorization = `Bearer ${accessToken}`
  return cfg
})
http.interceptors.response.use(
  (res) => res.data,
  (err) => {
    if (err.response?.status === 401) { /* TODO: 導向登入頁 */ }
    return Promise.reject(err)
  }
)
export default http
