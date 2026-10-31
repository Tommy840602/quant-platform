import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { accountApi } from '@/api'

/** 帳戶：虛擬 (paper) / 真實 (main) 切換 + 餘額 / 部位（跟著目前模式）。 */
export const useAccountStore = defineStore('account', () => {
  const balance = ref(null)
  const positions = ref([])
  const mode = ref('paper')            // paper | main
  const modeInfo = ref({})             // { label, realConfigured, env }
  const isPaper = computed(() => mode.value !== 'main')
  const label = computed(() => isPaper.value ? '虛擬帳戶' : '真實帳戶')

  async function loadMode() {
    try {
      const r = await accountApi.getMode()
      if (r.data?.mode) { mode.value = r.data.mode; modeInfo.value = r.data }
    } catch {}
    return mode.value
  }
  /** 切換；後端拒絕（真實帳戶沒金鑰）會丟錯，由呼叫端顯示。 */
  async function setMode(m) {
    const r = await accountApi.setMode(m)
    if (r.code && r.code !== 0) throw new Error(r.message)
    mode.value = r.data.mode; modeInfo.value = r.data
    await refresh()
    return r.data
  }
  async function refresh() {
    balance.value = (await accountApi.balance()).data
    positions.value = (await accountApi.positions()).data || []
  }
  return { balance, positions, mode, modeInfo, isPaper, label, loadMode, setMode, refresh }
})
