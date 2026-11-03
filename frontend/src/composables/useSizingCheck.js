import { SYMBOLS } from '@/config/symbols'

/** 幣安現貨多數交易對的最低名目（MIN_NOTIONAL / NOTIONAL filter）。 */
export const MIN_NOTIONAL = 5

/**
 * 下單金額預檢：在「真的送出去被幣安拒絕」之前，先告訴你這組設定一定會被拒。
 * notional > 0：每單 = notional USDT，低於 5 一定被拒。
 * notional = 0：每單 = 策略固定數量 qty × 現價，找出最小的那個標的。
 * 回 null = 沒問題；否則回 { kind, symbol, qty, value, notional }。
 */
export function sizingProblem(bot, notional, prices) {
  const n = Number(notional) || 0
  if (n > 0) return n < MIN_NOTIONAL ? { kind: 'notional', notional: n } : null
  const qty = Number(bot?.params?.qty ?? 0.001) || 0.001
  const syms = !bot?.symbol || bot.symbol === 'ALL' ? SYMBOLS : [bot.symbol]
  let worst = null
  for (const s of syms) {
    const px = prices[s]
    if (!px) continue
    const v = qty * px
    if (!worst || v < worst.value) worst = { symbol: s, value: v }
  }
  if (!worst) return null
  return worst.value < MIN_NOTIONAL ? { kind: 'fixed', qty, ...worst } : null
}
