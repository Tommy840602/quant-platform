import { defineStore } from 'pinia'
import { ref } from 'vue'
import { authApi } from '@/api'
import { setToken } from '@/api/http'

/** 認證 store：token 僅存記憶體（見架構文件 §11.3）。 */
export const useAuthStore = defineStore('auth', () => {
  const username = ref('')
  const loggedIn = ref(false)

  async function login(u, p) {
    const resp = await authApi.login({ username: u, password: p })
    const token = resp.data?.accessToken
    if (!token) throw new Error('no token')
    setToken(token)
    username.value = resp.data.username || u
    loggedIn.value = true
  }
  function logout() { setToken(null); loggedIn.value = false; username.value = '' }
  return { username, loggedIn, login, logout }
})
