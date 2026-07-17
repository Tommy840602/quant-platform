// 技術指標計算（見架構文件 §11 技術圖分析）
export function sma(v, p) {
  const out = []
  for (let i = 0; i < v.length; i++) {
    if (i < p - 1) { out.push(null); continue }
    let s = 0; for (let j = i - p + 1; j <= i; j++) s += v[j]
    out.push(s / p)
  }
  return out
}
export function ema(v, p) {
  const out = []; const k = 2 / (p + 1); let prev
  for (let i = 0; i < v.length; i++) {
    if (i === 0) { prev = v[0]; out.push(prev); continue }
    prev = v[i] * k + prev * (1 - k); out.push(prev)
  }
  return out
}
export function boll(v, p = 20, mult = 2) {
  const mid = sma(v, p), upper = [], lower = []
  for (let i = 0; i < v.length; i++) {
    if (mid[i] == null) { upper.push(null); lower.push(null); continue }
    let s = 0; for (let j = i - p + 1; j <= i; j++) s += (v[j] - mid[i]) ** 2
    const sd = Math.sqrt(s / p)
    upper.push(mid[i] + mult * sd); lower.push(mid[i] - mult * sd)
  }
  return { mid, upper, lower }
}
export function macd(v, fast = 12, slow = 26, signal = 9) {
  const ef = ema(v, fast), es = ema(v, slow)
  const dif = v.map((_, i) => ef[i] - es[i])
  const dea = ema(dif, signal)
  const hist = dif.map((d, i) => (d - dea[i]) * 2)
  return { dif, dea, hist }
}
export function rsi(v, p = 14) {
  const out = []
  for (let i = 0; i < v.length; i++) {
    if (i < p) { out.push(null); continue }
    let g = 0, l = 0
    for (let j = i - p + 1; j <= i; j++) { const d = v[j] - v[j - 1]; if (d >= 0) g += d; else l -= d }
    const rs = l === 0 ? 100 : (g / p) / (l / p)
    out.push(100 - 100 / (1 + rs))
  }
  return out
}
export function kdj(high, low, close, p = 9) {
  const K = [], D = [], J = []; let pk = 50, pd = 50
  for (let i = 0; i < close.length; i++) {
    if (i < p - 1) { K.push(null); D.push(null); J.push(null); continue }
    let hh = -Infinity, ll = Infinity
    for (let j = i - p + 1; j <= i; j++) { hh = Math.max(hh, high[j]); ll = Math.min(ll, low[j]) }
    const rsv = hh === ll ? 50 : (close[i] - ll) / (hh - ll) * 100
    pk = 2 / 3 * pk + 1 / 3 * rsv; pd = 2 / 3 * pd + 1 / 3 * pk
    K.push(pk); D.push(pd); J.push(3 * pk - 2 * pd)
  }
  return { K, D, J }
}

export function atr(high, low, close, p = 14) {
  const tr = [], out = []
  for (let i = 0; i < close.length; i++) {
    tr.push(i === 0 ? high[i] - low[i] : Math.max(high[i] - low[i], Math.abs(high[i] - close[i - 1]), Math.abs(low[i] - close[i - 1])))
    if (i < p - 1) { out.push(null); continue }
    let s = 0; for (let j = i - p + 1; j <= i; j++) s += tr[j]; out.push(s / p)
  }
  return out
}
export function cci(high, low, close, p = 20) {
  const tp = close.map((c, i) => (high[i] + low[i] + c) / 3), out = []
  for (let i = 0; i < tp.length; i++) {
    if (i < p - 1) { out.push(null); continue }
    let m = 0; for (let j = i - p + 1; j <= i; j++) m += tp[j]; m /= p
    let md = 0; for (let j = i - p + 1; j <= i; j++) md += Math.abs(tp[j] - m); md /= p
    out.push(md === 0 ? 0 : (tp[i] - m) / (0.015 * md))
  }
  return out
}
export function williamsR(high, low, close, p = 14) {
  const out = []
  for (let i = 0; i < close.length; i++) {
    if (i < p - 1) { out.push(null); continue }
    let hh = -Infinity, ll = Infinity
    for (let j = i - p + 1; j <= i; j++) { hh = Math.max(hh, high[j]); ll = Math.min(ll, low[j]) }
    out.push(hh === ll ? -50 : (hh - close[i]) / (hh - ll) * -100)
  }
  return out
}
export function obv(close, volume) {
  const out = [0]
  for (let i = 1; i < close.length; i++) out.push(out[i - 1] + (close[i] > close[i - 1] ? volume[i] : close[i] < close[i - 1] ? -volume[i] : 0))
  return out
}
export function stochRsi(close, p = 14) {
  const r = rsi(close, p), out = []
  for (let i = 0; i < r.length; i++) {
    if (i < p * 2 || r[i] == null) { out.push(null); continue }
    let hh = -Infinity, ll = Infinity
    for (let j = i - p + 1; j <= i; j++) { if (r[j] == null) continue; hh = Math.max(hh, r[j]); ll = Math.min(ll, r[j]) }
    out.push(hh === ll ? 50 : (r[i] - ll) / (hh - ll) * 100)
  }
  return out
}

export function vwap(high, low, close, volume, p = 20) {
  const tp = close.map((c, i) => (high[i] + low[i] + c) / 3), out = []
  for (let i = 0; i < close.length; i++) {
    if (i < p - 1) { out.push(null); continue }
    let pv = 0, v = 0
    for (let j = i - p + 1; j <= i; j++) { pv += tp[j] * volume[j]; v += volume[j] }
    out.push(v === 0 ? null : pv / v)
  }
  return out
}
export function roc(close, p = 12) {
  return close.map((c, i) => i < p ? null : (c / close[i - p] - 1) * 100)
}
export function trix(close, p = 15) {
  const e1 = ema(close, p), e2 = ema(e1, p), e3 = ema(e2, p), out = [null]
  for (let i = 1; i < e3.length; i++) out.push(e3[i - 1] ? (e3[i] - e3[i - 1]) / e3[i - 1] * 100 : 0)
  return out
}
export function cmf(high, low, close, volume, p = 20) {
  const out = []
  for (let i = 0; i < close.length; i++) {
    if (i < p - 1) { out.push(null); continue }
    let mfv = 0, v = 0
    for (let j = i - p + 1; j <= i; j++) {
      const hl = high[j] - low[j]
      mfv += (hl === 0 ? 0 : ((close[j] - low[j]) - (high[j] - close[j])) / hl) * volume[j]; v += volume[j]
    }
    out.push(v === 0 ? 0 : mfv / v)
  }
  return out
}
