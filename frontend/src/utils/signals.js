import { sma, ema, macd, rsi, boll, kdj, atr, cci, williamsR } from './indicators'

/** 依策略類型從 K 線算買賣信號，回傳 [{i, side}]。與後端策略邏輯一致（見架構文件 §7.4）。 */
export function signals(type, k, params = {}) {
  const c = k.map(x => x.close), h = k.map(x => x.high), l = k.map(x => x.low)
  const out = []
  const push = (i, side) => out.push({ i, side })
  const n = c.length

  switch (type) {
    case 'ma': {
      const f = sma(c, params.fast || 5), s = sma(c, params.slow || 20)
      for (let i = 1; i < n; i++) { if ([f[i], s[i], f[i - 1], s[i - 1]].some(x => x == null)) continue
        if (f[i - 1] <= s[i - 1] && f[i] > s[i]) push(i, 'BUY'); else if (f[i - 1] >= s[i - 1] && f[i] < s[i]) push(i, 'SELL') }
      break }
    case 'ema': {
      const f = ema(c, params.fast || 12), s = ema(c, params.slow || 26)
      for (let i = 1; i < n; i++) { if (f[i - 1] <= s[i - 1] && f[i] > s[i]) push(i, 'BUY'); else if (f[i - 1] >= s[i - 1] && f[i] < s[i]) push(i, 'SELL') }
      break }
    case 'macd': {
      const m = macd(c)
      for (let i = 1; i < n; i++) { if (m.hist[i - 1] <= 0 && m.hist[i] > 0) push(i, 'BUY'); else if (m.hist[i - 1] >= 0 && m.hist[i] < 0) push(i, 'SELL') }
      break }
    case 'rsi': {
      const r = rsi(c, 14); let pos = false
      for (let i = 0; i < n; i++) { if (r[i] == null) continue; if (r[i] < 30 && !pos) { push(i, 'BUY'); pos = true } else if (r[i] > 70 && pos) { push(i, 'SELL'); pos = false } }
      break }
    case 'boll': {
      const b = boll(c, 20, 2); let pos = false
      for (let i = 0; i < n; i++) { if (b.lower[i] == null) continue; if (c[i] <= b.lower[i] && !pos) { push(i, 'BUY'); pos = true } else if (c[i] >= b.upper[i] && pos) { push(i, 'SELL'); pos = false } }
      break }
    case 'bollbreak': {
      const b = boll(c, 20, 2)
      for (let i = 0; i < n; i++) { if (b.upper[i] == null) continue; if (c[i] > b.upper[i]) push(i, 'BUY'); else if (c[i] < b.lower[i]) push(i, 'SELL') }
      break }
    case 'kdj': {
      const kd = kdj(h, l, c); let pos = false
      for (let i = 0; i < n; i++) { if (kd.J[i] == null) continue; if (kd.J[i] < 20 && !pos) { push(i, 'BUY'); pos = true } else if (kd.J[i] > 80 && pos) { push(i, 'SELL'); pos = false } }
      break }
    case 'cci': {
      const v = cci(h, l, c, 20); let pos = false
      for (let i = 0; i < n; i++) { if (v[i] == null) continue; if (v[i] < -100 && !pos) { push(i, 'BUY'); pos = true } else if (v[i] > 100 && pos) { push(i, 'SELL'); pos = false } }
      break }
    case 'williams': {
      const w = williamsR(h, l, c, 14); let pos = false
      for (let i = 0; i < n; i++) { if (w[i] == null) continue; if (w[i] < -80 && !pos) { push(i, 'BUY'); pos = true } else if (w[i] > -20 && pos) { push(i, 'SELL'); pos = false } }
      break }
    case 'donchian': {
      const p = 20
      for (let i = p; i < n; i++) { let hh = -Infinity, ll = Infinity; for (let j = i - p; j < i; j++) { hh = Math.max(hh, h[j]); ll = Math.min(ll, l[j]) }
        if (c[i] > hh) push(i, 'BUY'); else if (c[i] < ll) push(i, 'SELL') }
      break }
    case 'supertrend': {
      const a = atr(h, l, c, 10), mult = 3; let trend = 1, st = 0
      for (let i = 0; i < n; i++) { if (a[i] == null) continue; const mid = (h[i] + l[i]) / 2; const up = mid + mult * a[i], dn = mid - mult * a[i]
        const prev = trend; if (c[i] > st) trend = 1; else if (c[i] < st) trend = -1; st = trend === 1 ? dn : up
        if (prev < 0 && trend > 0) push(i, 'BUY'); else if (prev > 0 && trend < 0) push(i, 'SELL') }
      break }
  }
  return out
}

// 圖上有信號的策略清單
export const SIGNAL_TYPES = [
  { value: '', label: '無' }, { value: 'ma', label: 'MA 交叉' }, { value: 'ema', label: 'EMA 交叉' },
  { value: 'macd', label: 'MACD' }, { value: 'rsi', label: 'RSI' }, { value: 'boll', label: '布林回歸' },
  { value: 'bollbreak', label: '布林突破' }, { value: 'kdj', label: 'KDJ' }, { value: 'cci', label: 'CCI' },
  { value: 'williams', label: '威廉%R' }, { value: 'donchian', label: '唐奇安突破' }, { value: 'supertrend', label: 'SuperTrend' }
]
