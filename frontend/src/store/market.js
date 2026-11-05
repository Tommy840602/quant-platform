import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'
import { SYMBOLS } from '@/config/symbols'

/**
 * 行情 store：訂閱 20 對 miniTicker（幣安正式網公開行情），提供即時價與 24h 漲跌幅。
 * prices[symbol]=最新價；stats[symbol]={price, changePct, open}。
 *
 * 連線端點依序嘗試，連不上（或連上沒收到資料就斷）自動換下一個：
 * 部分網路（公司、部分電信）會擋 stream.binance.com，data-stream.binance.vision 是幣安純行情專用網域。
 */
const BASES = [
  'wss://data-stream.binance.vision',
  'wss://stream.binance.com:9443',
  'wss://stream.binance.com:443',
]
const STALE_MS = 30000   // 20 對 miniTicker 正常每秒都有；30 秒沒資料視為殭屍連線

export const useMarketStore = defineStore('market', () => {
  const connected = ref(false)
  const endpoint = ref('')
  const prices = reactive({})
  const stats = reactive({})
  let ws = null
  let retry = 0
  let idx = 0
  let gotMessage = false
  let lastMsgAt = 0
  let timer = null
  let started = false

  function connect() {
    clearTimeout(timer); timer = null
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return
    const streams = SYMBOLS.map(s => `${s.toLowerCase()}@miniTicker`).join('/')
    const base = BASES[idx % BASES.length]
    endpoint.value = base
    gotMessage = false
    const sock = new WebSocket(`${base}/stream?streams=${streams}`)
    ws = sock
    sock.onopen = () => { lastMsgAt = Date.now() }
    sock.onmessage = (e) => {
      gotMessage = true; lastMsgAt = Date.now()
      if (!connected.value) { connected.value = true; retry = 0 }
      const d = JSON.parse(e.data)?.data
      if (!d || d.e !== '24hrMiniTicker') return
      const c = Number(d.c), o = Number(d.o)
      prices[d.s] = c
      stats[d.s] = { price: c, open: o, changePct: o ? (c / o - 1) * 100 : 0 }
    }
    sock.onclose = () => {
      if (ws !== sock) return
      connected.value = false
      ws = null
      if (!gotMessage) idx++          // 這個端點沒拿到資料 → 下次換下一個
      reconnect()
    }
    sock.onerror = () => { try { sock.close() } catch { /* ignore */ } }
  }

  function reconnect(now = false) {
    clearTimeout(timer)
    // 換端點時先快速試下一個；整輪都失敗才開始退避
    const delay = now ? 0 : (!gotMessage && idx % BASES.length !== 0) ? 500
      : Math.min(30000, 1000 * 2 ** Math.min(retry++, 5))
    timer = setTimeout(connect, delay)
  }

  /** 分頁回到前景 / 網路恢復時立刻重連，不等退避；也檢查殭屍連線。 */
  function kick() {
    if (ws && ws.readyState === WebSocket.OPEN && Date.now() - lastMsgAt > STALE_MS) { ws.close(); return }
    if (!ws) { retry = 0; reconnect(true) }
  }

  function start() {
    if (started) return connect()
    started = true
    window.addEventListener('online', kick)
    document.addEventListener('visibilitychange', () => { if (!document.hidden) kick() })
    setInterval(() => { if (!document.hidden) kick() }, 10000)
    connect()
  }

  return { connected, endpoint, prices, stats, connect: start }
})
