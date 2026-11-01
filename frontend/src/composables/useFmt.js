import { ref, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'

/**
 * 執行時長 / 收益 的顯示格式（各頁共用）。
 * fmtDur：超過一天 → 「N天 HH:MM:SS」，否則 HH:MM:SS（不足一小時 MM:SS）；秒數每秒跳動。
 * fmtPnl：「賺 +1.23 USDT (+1.00%)」/「虧 −0.56 USDT (−0.40%)」，顏色綠漲紅跌。
 */
export function useFmt() {
  const { t } = useI18n()
  const tick = ref(0)
  const timer = setInterval(() => { tick.value++ }, 1000)
  onUnmounted(() => clearInterval(timer))

  const pad = n => String(n).padStart(2, '0')
  /** @param sec 後端回的秒數 @param fetchedAt 取得時間(ms)，用來在兩次刷新之間本地累加 */
  const fmtDur = (sec, fetchedAt) => {
    void tick.value
    let s = Math.max(0, Math.floor(+sec || 0) + (fetchedAt ? Math.floor((Date.now() - fetchedAt) / 1000) : 0))
    const d = Math.floor(s / 86400); s %= 86400
    const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), ss = s % 60
    const clock = h ? `${pad(h)}:${pad(m)}:${pad(ss)}` : `${pad(m)}:${pad(ss)}`
    return d ? t('common.days', { d }) + ' ' + clock : clock
  }

  const num = v => (v > 0 ? '+' : v < 0 ? '−' : '') + Math.abs(Number(v || 0)).toFixed(2)
  const pct = v => (v > 0 ? '+' : v < 0 ? '−' : '') + (Math.abs(Number(v || 0)) * 100).toFixed(2) + '%'
  const fmtPnl = p => {
    if (!p) return null
    const v = Number(p.pnl || 0)
    const word = v > 0 ? t('common.gain') : v < 0 ? t('common.loss') : t('common.flat')
    return { word, text: `${num(v)} USDT (${pct(p.pnlPct)})`, color: v > 0 ? '#67c23a' : v < 0 ? '#f56c6c' : 'inherit', num, pct }
  }
  return { fmtDur, fmtPnl, num, pct }
}
