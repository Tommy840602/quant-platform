import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'
import { SYMBOLS } from '@/config/symbols'

/**
 * 行情 store：訂閱 20 對 miniTicker（幣安正式網公開行情），提供即時價與 24h 漲跌幅。
 * prices[symbol]=最新價；stats[symbol]={price, changePct, open}。
 */
export const useMarketStore = defineStore('market', () => {
  const connected = ref(false)
  const prices = reactive({})
  const stats = reactive({})
  let ws = null
  let retry = 0

  function connect() {
    const streams = SYMBOLS.map(s => `${s.toLowerCase()}@miniTicker`).join('/')
    ws = new WebSocket(`wss://stream.binance.com:9443/stream?streams=${streams}`)
    ws.onopen = () => { connected.value = true; retry = 0 }
    ws.onmessage = (e) => {
      const d = JSON.parse(e.data)?.data
      if (!d || d.e !== '24hrMiniTicker') return
      const c = Number(d.c), o = Number(d.o)
      prices[d.s] = c
      stats[d.s] = { price: c, open: o, changePct: o ? (c / o - 1) * 100 : 0 }
    }
    ws.onclose = () => { connected.value = false; reconnect() }
    ws.onerror = () => ws && ws.close()
  }
  function reconnect() {
    const delay = Math.min(30000, 1000 * 2 ** Math.min(retry++, 5))
    setTimeout(connect, delay)
  }
  return { connected, prices, stats, connect }
})
