import { defineStore } from 'pinia'
import { ref } from 'vue'
import { tradeConfigApi } from '@/api'

/** 下單連線設定（env / dryRun / keySet）單一來源：右上角、帳戶頁、連線設定頁共用，存檔後立即同步。 */
export const useTradeStore = defineStore('trade', () => {
  const cfg = ref({})
  async function load() {
    try { cfg.value = (await tradeConfigApi.get()).data || {} } catch { /* 保留舊值 */ }
    return cfg.value
  }
  async function save(body) {
    const r = await tradeConfigApi.set(body)
    if (r?.data) cfg.value = r.data      // POST 回的就是套用後的狀態，不用再 GET
    else await load()
    return cfg.value
  }
  return { cfg, load, save }
})
