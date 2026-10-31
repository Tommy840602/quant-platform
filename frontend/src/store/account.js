import { defineStore } from 'pinia'
import { ref } from 'vue'
import { accountApi } from '@/api'

export const useAccountStore = defineStore('account', () => {
  const balance = ref(null)
  const positions = ref([])
  async function refresh() {
    balance.value = (await accountApi.balance()).data
    positions.value = (await accountApi.positions()).data || []
  }
  return { balance, positions, refresh }
})
