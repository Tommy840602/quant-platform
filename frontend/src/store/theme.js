import { defineStore } from 'pinia'
import { ref } from 'vue'

/** 日/夜主題（TradingView 風格），偏好存 localStorage。 */
export const useThemeStore = defineStore('theme', () => {
  const saved = localStorage.getItem('qp-theme')
  const dark = ref(saved ? saved === 'dark' : true)
  function apply() {
    document.documentElement.classList.toggle('dark', dark.value)
    localStorage.setItem('qp-theme', dark.value ? 'dark' : 'light')
  }
  function toggle() { dark.value = !dark.value; apply() }
  apply()
  return { dark, toggle, apply }
})
