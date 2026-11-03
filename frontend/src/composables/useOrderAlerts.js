import { ElNotification } from 'element-plus'
import { orderApi } from '@/api'

/**
 * 網頁內跳出通知：每 15 秒看一次最近訂單，有「新的」REJECTED / UNKNOWN 就跳通知。
 * 同一原因合併成一則（例：ma-fast 12 筆被拒：名目低於 5 USDT），不會一單一則洗版。
 * 只通知開頁之後才出現的單；點通知跳到訂單紀錄。
 */
export function useOrderAlerts({ t, router, enabled }) {
  const seen = new Set()
  let primed = false, timer = null

  async function poll() {
    if (!enabled()) return
    let rows
    try { rows = (await orderApi.recent(100)).data || [] } catch { return }
    const fresh = []
    for (const r of rows) {
      if (seen.has(r.clientOrderId)) continue
      seen.add(r.clientOrderId)
      if (primed && (r.status === 'REJECTED' || r.status === 'UNKNOWN')) fresh.push(r)
    }
    primed = true
    if (!fresh.length) return
    const groups = new Map()
    for (const r of fresh) {
      const k = r.status + '|' + (r.reason || '') + '|' + (r.strategyId || '')
      const g = groups.get(k) || { status: r.status, reason: r.reason, strategy: r.strategyId, symbols: [] }
      g.symbols.push(r.symbol); groups.set(k, g)
    }
    const list = [...groups.values()]
    const rejected = fresh.filter(r => r.status === 'REJECTED').length
    const lines = list.map(g => {
      const syms = g.symbols.slice(0, 4).join(', ') + (g.symbols.length > 4 ? ` +${g.symbols.length - 4}` : '')
      return `${g.strategy ? '[' + g.strategy + '] ' : ''}${syms}（${g.symbols.length}）\n→ ${g.reason || t('alerts.noReason')}`
    })
    ElNotification({
      type: rejected ? 'error' : 'warning',
      title: rejected ? t('alerts.rejected', { n: rejected }) : t('alerts.unknown', { n: fresh.length }),
      message: lines.join('\n\n'),
      duration: rejected ? 20000 : 0,
      customClass: 'order-alert',
      onClick: () => router.push('/orders')
    })
  }
  function start() { if (timer) return; poll(); timer = setInterval(poll, 15000) }
  return { start }
}
