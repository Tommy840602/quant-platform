import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '@/api'
import { setToken, getToken } from '@/api/http'

/** 認證 store：token 記憶體 + sessionStorage（見架構文件 §11.3）。 */
export const useAuthStore = defineStore('auth', () => {
  const username = ref(sessionStorage.getItem('qp-user') || '')
  const roles = ref((sessionStorage.getItem('qp-roles') || '').split(',').filter(Boolean))
  const loggedIn = ref(!!getToken())
  const isAdmin = computed(() => roles.value.includes('ADMIN'))

  async function login(u, p) {
    const resp = await authApi.login({ username: u, password: p })
    if (resp.code !== 0 || !resp.data?.accessToken) throw new Error(resp.message || '登入失敗')
    setToken(resp.data.accessToken)
    username.value = resp.data.username || u
    roles.value = resp.data.roles || []
    sessionStorage.setItem('qp-user', username.value)
    sessionStorage.setItem('qp-roles', roles.value.join(','))
    loggedIn.value = true
  }
  function logout() {
    setToken(null); loggedIn.value = false; username.value = ''; roles.value = []
    sessionStorage.removeItem('qp-user'); sessionStorage.removeItem('qp-roles')
  }
  return { username, roles, loggedIn, isAdmin, login, logout }
})
