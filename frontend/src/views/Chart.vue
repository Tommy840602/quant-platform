<template>
  <div>
    <el-card>
      <template #header>
        <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap">
          <span>{{ t('chart.s2') }}</span>
          <symbol-select v-model="symbol" style="width:150px" @change="reload" />
          <el-select v-model="interval" style="width:90px" @change="reload">
            <el-option v-for="i in ['1m','5m','15m','1h','4h','1d']" :key="i" :label="i" :value="i" />
          </el-select>
          <el-select v-model="sigType" style="width:130px" @change="draw" :placeholder="t('chart.s1')">
            <el-option v-for="s in SIGNAL_TYPES" :key="s.value" :label="t('chart.s9')+s.label" :value="s.value" />
          </el-select>
          <span class="px"><coin-icon :symbol="symbol" :size="18" /> <price-change :symbol="symbol" /></span>
          <el-checkbox-group v-model="overlays" size="small" @change="draw">
            <el-checkbox-button label="MA">MA</el-checkbox-button>
            <el-checkbox-button label="EMA">EMA</el-checkbox-button>
            <el-checkbox-button label="BOLL">BOLL</el-checkbox-button>
            <el-checkbox-button label="VWAP">VWAP</el-checkbox-button>
          </el-checkbox-group>
          <el-checkbox-group v-model="panes" size="small" @change="reload">
            <el-checkbox-button label="VOL">{{ t('chart.s3') }}</el-checkbox-button>
            <el-checkbox-button label="MACD">MACD</el-checkbox-button>
            <el-checkbox-button label="RSI">RSI</el-checkbox-button>
            <el-checkbox-button label="KDJ">KDJ</el-checkbox-button>
            <el-checkbox-button label="ATR">ATR</el-checkbox-button>
            <el-checkbox-button label="CCI">CCI</el-checkbox-button>
            <el-checkbox-button label="WR">{{ t('chart.s4') }}</el-checkbox-button>
            <el-checkbox-button label="OBV">OBV</el-checkbox-button>
            <el-checkbox-button label="TRIX">TRIX</el-checkbox-button>
            <el-checkbox-button label="ROC">ROC</el-checkbox-button>
            <el-checkbox-button label="CMF">CMF</el-checkbox-button>
          </el-checkbox-group>
          <el-checkbox v-model="showDec" @change="loadDecisions">{{ t('decisions.onChartToggle') }}</el-checkbox>
          <el-select v-if="showDec" v-model="decBot" clearable :placeholder="t('decisions.allBots')" style="width:150px" @change="loadDecisions">
            <el-option v-for="b in decBots" :key="b" :label="b" :value="b" />
          </el-select>
          <el-button size="small" @click="reload">{{ t('chart.s5') }}</el-button>
        </div>
      </template>
      <div class="chart-wrap">
        <div class="chart-legend" v-html="legend"></div>
        <div ref="priceEl" style="height:340px"></div>
      </div>
      <div v-if="showDec && replayList.length" class="replay">
        <el-button size="small" :disabled="cur <= 0" @click="step(-1)">◀ {{ t('decisions.prev') }}</el-button>
        <div class="replay-body" v-if="curDec">
          <b>{{ cur + 1 }}/{{ replayList.length }}</b>
          <span>{{ locale.fmtTime(curDec.ts, { year: undefined }) }}</span>
          <el-tag size="small" effect="plain">{{ curDec.botId }}</el-tag>
          <span :style="{ color: curDec.side === 'BUY' ? '#26a69a' : '#ef5350', fontWeight: 600 }">{{ curDec.side }}</span>
          <span>@{{ curDec.price ? +curDec.price.toPrecision(6) : '' }}</span>
          <span :style="{ color: curDec.acted ? '#67c23a' : 'var(--el-text-color-secondary)' }">{{ curDec.outcome }}</span>
          <span class="hint" v-if="curDec.detail?.regime">{{ t('regime.' + curDec.detail.regime) }}<template v-if="curDec.detail.adx != null"> · ADX {{ curDec.detail.adx }}</template> · {{ t('regime.vol') }} {{ curDec.detail.volRatio }}×</span>
        </div>
        <el-checkbox v-model="actedOnly" size="small" @change="cur = Math.max(0, replayList.length - 1); focus()">{{ t('decisions.actedOnly') }}</el-checkbox>
        <el-button size="small" :disabled="cur >= replayList.length - 1" @click="step(1)">{{ t('decisions.next') }} ▶</el-button>
      </div>
      <div v-show="panes.includes('VOL')" ref="volEl" style="height:100px;margin-top:6px"></div>
      <div v-show="panes.includes('MACD')" ref="macdEl" style="height:110px;margin-top:6px"></div>
      <div v-show="panes.includes('RSI')" ref="rsiEl" style="height:100px;margin-top:6px"></div>
      <div v-show="panes.includes('KDJ')" ref="kdjEl" style="height:100px;margin-top:6px"></div>
      <div v-show="panes.includes('ATR')" ref="atrEl" style="height:90px;margin-top:6px"></div>
      <div v-show="panes.includes('CCI')" ref="cciEl" style="height:90px;margin-top:6px"></div>
      <div v-show="panes.includes('WR')" ref="wrEl" style="height:90px;margin-top:6px"></div>
      <div v-show="panes.includes('OBV')" ref="obvEl" style="height:90px;margin-top:6px"></div>
      <div v-show="panes.includes('TRIX')" ref="trixEl" style="height:90px;margin-top:6px"></div>
      <div v-show="panes.includes('ROC')" ref="rocEl" style="height:90px;margin-top:6px"></div>
      <div v-show="panes.includes('CMF')" ref="cmfEl" style="height:90px;margin-top:6px"></div>
      <div class="hint">{{ t('chart.s6') }}</div>
    </el-card>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { createChart as lwCreate } from 'lightweight-charts'
import { observeWidth } from '@/utils/resize'
const unobs = []
// 每張圖跟著容器寬度走（手機轉向、側欄收合）
function createChart(el, opts) { const c = lwCreate(el, opts); unobs.push(observeWidth(el, w => c.applyOptions({ width: w }))); return c }
import { storeToRefs } from 'pinia'
import { marketApi, orderApi, strategyApi } from '@/api'
import { useRoute } from 'vue-router'
import { useLocaleStore } from '@/store/locale'
import { useMarketStore } from '@/store/market'
import { SYMBOLS } from '@/config/symbols'
import * as ind from '@/utils/indicators'
import { signals, SIGNAL_TYPES } from '@/utils/signals'
import { useThemeStore } from '@/store/theme'

const theme = useThemeStore()
const market = useMarketStore()
const { prices } = storeToRefs(market)

const route = useRoute()
const locale = useLocaleStore()
const symbol = ref(typeof route.query.symbol === 'string' ? route.query.symbol : 'BTCUSDT'); const interval = ref('1m')
// ── 機器人決策疊圖 + 回放 ──
const IV = { '1m': 60, '5m': 300, '15m': 900, '1h': 3600, '4h': 14400, '1d': 86400 }
const showDec = ref(true), decBot = ref(typeof route.query.bot === 'string' ? route.query.bot : ''), actedOnly = ref(false)
const decisions = ref([]), cur = ref(0)
let focusTs = route.query.t ? Number(route.query.t) : 0
const decBots = computed(() => [...new Set(decisions.value.map(d => d.botId))])
const replayList = computed(() => decisions.value.filter(d => !actedOnly.value || d.acted))
const curDec = computed(() => replayList.value[cur.value])
const barTime = ms => Math.floor(ms / 1000 / IV[interval.value]) * IV[interval.value]
async function loadDecisions() {
  if (!showDec.value || !raw.length) { decisions.value = []; markSignals(raw.map(k => Math.floor(k.openTime / 1000))); return }
  const from = raw[0].openTime, to = Date.now()
  try {
    const rows = (await strategyApi.decisions({ symbol: symbol.value, botId: decBot.value || undefined, from, to, limit: 2000 })).data || []
    decisions.value = rows.slice().sort((a, b) => a.ts - b.ts)
  } catch { decisions.value = [] }
  const list = replayList.value
  if (focusTs) { let best = 0; list.forEach((d, i) => { if (Math.abs(d.ts - focusTs) < Math.abs(list[best].ts - focusTs)) best = i }); cur.value = best; focusTs = 0 }
  else cur.value = Math.max(0, list.length - 1)
  markSignals(raw.map(k => Math.floor(k.openTime / 1000)))
  if (route.query.t) focus()
}
function step(d) { cur.value = Math.min(replayList.value.length - 1, Math.max(0, cur.value + d)); focus() }
function focus() {
  const d = curDec.value
  if (!d || !priceChart) return
  const tt = barTime(d.ts), span = IV[interval.value] * 60
  try { priceChart.timeScale().setVisibleRange({ from: tt - span, to: tt + span / 2 }) } catch {}
  markSignals(raw.map(k => Math.floor(k.openTime / 1000)))
}
const overlays = ref(['MA', 'BOLL'])
const sigType = ref('ma')
const legend = ref('')
const panes = ref(['VOL', 'MACD', 'RSI'])
const priceEl = ref(null), volEl = ref(null), macdEl = ref(null), rsiEl = ref(null), kdjEl = ref(null)
const atrEl = ref(null), cciEl = ref(null), wrEl = ref(null), obvEl = ref(null)
const trixEl = ref(null), rocEl = ref(null), cmfEl = ref(null)

let priceChart, candle, series = {}
let volChart, volSeries, macdChart, macdHist, macdDif, macdDea
let rsiChart, rsiSeries, kdjChart, kSer, dSer, jSer
let atrChart, atrSeries, cciChart, cciSeries, wrChart, wrSeries, obvChart, obvSeries
let trixChart, trixSeries, rocChart, rocSeries, cmfChart, cmfSeries, vwapS
let raw = []

const lastPrice = computed(() => prices.value[symbol.value])
const line = (arr, t, color, w = 1) => arr.map((v, i) => v == null ? null : ({ time: t[i], value: v })).filter(Boolean)

async function reload() {
  let resp
  const sec = IV[interval.value]
  if (focusTs && Date.now() - focusTs > sec * 1000 * 250) {
    resp = await marketApi.klinesRange(symbol.value, interval.value, focusTs - sec * 1000 * 200, focusTs + sec * 1000 * 100, 400)
  } else resp = await marketApi.klines(symbol.value, interval.value, 300)
  raw = resp.data || []
  await nextTick()
  ensurePanes()
  draw()
  loadDecisions()
}

function chartOpts() {
  const dark = theme.dark
  return {
    layout: { background: { type: 'solid', color: dark ? '#131722' : '#ffffff' }, textColor: dark ? '#d1d4dc' : '#333' },
    grid: { vertLines: { color: dark ? '#2a2e39' : '#f0f0f0' }, horzLines: { color: dark ? '#2a2e39' : '#f0f0f0' } },
    rightPriceScale: { borderColor: dark ? '#2a2e39' : '#e0e0e0' },
    timeScale: { borderColor: dark ? '#2a2e39' : '#e0e0e0', timeVisible: true,
      // 軸刻度與十字線時間跟著「地區與偏好」的時區（lightweight-charts 預設是 UTC）
      tickMarkFormatter: (tt, type) => new Intl.DateTimeFormat(locale.lang, { timeZone: locale.tz, hour12: false,
        ...(type >= 3 ? { hour: '2-digit', minute: '2-digit' } : type === 2 ? { month: '2-digit', day: '2-digit' } : { year: 'numeric', month: '2-digit' }) }).format(tt * 1000) },
    localization: { timeFormatter: tt => locale.fmtTime(tt * 1000, { year: undefined }) }
  }
}
function applyTheme() {
  const opts = chartOpts()
  ;[priceChart, volChart, macdChart, rsiChart, kdjChart, atrChart, cciChart, wrChart, obvChart, trixChart, rocChart, cmfChart]
    .forEach(c => c && c.applyOptions(opts))
  if (candle) candle.applyOptions({
    upColor: '#26a69a', downColor: '#ef5350', borderUpColor: '#26a69a', borderDownColor: '#ef5350',
    wickUpColor: '#26a69a', wickDownColor: '#ef5350'
  })
}

function updateLegend(bar) {
  if (!bar) { if (raw.length) bar = { open: raw.at(-1).open, high: raw.at(-1).high, low: raw.at(-1).low, close: raw.at(-1).close }; else { legend.value = ''; return } }
  const chg = bar.open ? (bar.close / bar.open - 1) * 100 : 0
  const cls = bar.close >= bar.open ? 'up' : 'dn'
  const f = v => (typeof v === 'number' ? v : Number(v)).toLocaleString(undefined, { maximumFractionDigits: 2 })
  legend.value = `<b>${symbol.value}</b> · ${interval.value}　`
    + `<span class="${cls}">O ${f(bar.open)}　H ${f(bar.high)}　L ${f(bar.low)}　C ${f(bar.close)}　${chg >= 0 ? '+' : ''}${chg.toFixed(2)}%</span>`

}

function decisionMarkers() {
  if (!showDec.value) return []
  const focused = curDec.value
  return decisions.value.filter(d => !actedOnly.value || d.acted).map(d => {
    const isCur = focused && d === focused
    if (!d.acted) return { time: barTime(d.ts), position: 'aboveBar', shape: 'circle', color: isCur ? '#ffd600' : '#78909c', text: isCur ? '▶' : '' }
    const buy = d.side === 'BUY'
    return { time: barTime(d.ts), position: buy ? 'belowBar' : 'aboveBar', shape: buy ? 'arrowUp' : 'arrowDown',
      color: isCur ? '#ffd600' : buy ? '#2962ff' : '#ff6d00', text: (isCur ? '▶ ' : '') + d.botId }
  })
}

function markSignals(times) {
  if (!candle) return
  const dm = decisionMarkers()
  if (!sigType.value) { candle.setMarkers(dm.sort((a, b) => a.time - b.time)); return }
  const sigs = signals(sigType.value, raw)
  const markers = sigs.map(x => ({
    time: times[x.i],
    position: x.side === 'BUY' ? 'belowBar' : 'aboveBar',
    color: x.side === 'BUY' ? '#26a69a' : '#ef5350',
    shape: x.side === 'BUY' ? 'arrowUp' : 'arrowDown',
    text: x.side === 'BUY' ? t('chart.s7') : t('chart.s8')
  }))
  candle.setMarkers(markers.concat(dm).sort((a, b) => a.time - b.time))
}

function draw() {
  if (!raw.length) return
  applyTheme()
  const t = raw.map(k => Math.floor(k.openTime / 1000))
  const closes = raw.map(k => k.close), highs = raw.map(k => k.high), lows = raw.map(k => k.low)
  candle.setData(raw.map((k, i) => ({ time: t[i], open: k.open, high: k.high, low: k.low, close: k.close })))

  // 清掉舊疊圖
  Object.values(series).forEach(s => priceChart.removeSeries(s)); series = {}
  if (overlays.value.includes('MA')) {
    series.ma7 = priceChart.addLineSeries({ color: '#2e74b5', lineWidth: 1 }); series.ma7.setData(line(ind.sma(closes, 7), t))
    series.ma25 = priceChart.addLineSeries({ color: '#e6a23c', lineWidth: 1 }); series.ma25.setData(line(ind.sma(closes, 25), t))
    series.ma99 = priceChart.addLineSeries({ color: '#9b59b6', lineWidth: 1 }); series.ma99.setData(line(ind.sma(closes, 99), t))
  }
  if (overlays.value.includes('EMA')) {
    series.e12 = priceChart.addLineSeries({ color: '#16a085', lineWidth: 1 }); series.e12.setData(line(ind.ema(closes, 12), t))
    series.e26 = priceChart.addLineSeries({ color: '#c0392b', lineWidth: 1 }); series.e26.setData(line(ind.ema(closes, 26), t))
  }
  if (overlays.value.includes('BOLL')) {
    const b = ind.boll(closes, 20, 2)
    series.bu = priceChart.addLineSeries({ color: '#888', lineWidth: 1 }); series.bu.setData(line(b.upper, t))
    series.bm = priceChart.addLineSeries({ color: '#aaa', lineWidth: 1, lineStyle: 2 }); series.bm.setData(line(b.mid, t))
    series.bl = priceChart.addLineSeries({ color: '#888', lineWidth: 1 }); series.bl.setData(line(b.lower, t))
  }
  if (overlays.value.includes('VWAP')) { series.vwap = priceChart.addLineSeries({ color: '#e67e22', lineWidth: 1 }); series.vwap.setData(line(ind.vwap(highs, lows, closes, raw.map(k => k.volume), 20), t)) }
  if (volSeries) volSeries.setData(raw.map((k, i) => ({ time: t[i], value: k.volume, color: k.close >= k.open ? '#26a69a' : '#ef5350' })))
  if (macdHist) {
    const m = ind.macd(closes)
    macdHist.setData(m.hist.map((v, i) => ({ time: t[i], value: v, color: v >= 0 ? '#26a69a' : '#ef5350' })))
    macdDif.setData(line(m.dif, t)); macdDea.setData(line(m.dea, t))
  }
  if (rsiSeries) rsiSeries.setData(line(ind.rsi(closes, 14), t))
  if (kSer) {
    const k = ind.kdj(highs, lows, closes)
    kSer.setData(line(k.K, t)); dSer.setData(line(k.D, t)); jSer.setData(line(k.J, t))
  }
  if (atrSeries) atrSeries.setData(line(ind.atr(highs, lows, closes, 14), t))
  if (cciSeries) cciSeries.setData(line(ind.cci(highs, lows, closes, 20), t))
  if (wrSeries) wrSeries.setData(line(ind.williamsR(highs, lows, closes, 14), t))
  if (obvSeries) obvSeries.setData(line(ind.obv(closes, raw.map(k => k.volume)), t))
  if (trixSeries) trixSeries.setData(line(ind.trix(closes, 15), t))
  if (rocSeries) rocSeries.setData(line(ind.roc(closes, 12), t))
  if (cmfSeries) cmfSeries.setData(line(ind.cmf(highs, lows, closes, raw.map(k => k.volume), 20), t))
  markSignals(t)
  updateLegend(null)
}

function ensurePanes() {
  if (panes.value.includes('VOL') && !volChart) { volChart = createChart(volEl.value, { height: 100 }); volSeries = volChart.addHistogramSeries() }
  if (panes.value.includes('MACD') && !macdChart) {
    macdChart = createChart(macdEl.value, { height: 110 })
    macdHist = macdChart.addHistogramSeries(); macdDif = macdChart.addLineSeries({ color: '#2e74b5', lineWidth: 1 }); macdDea = macdChart.addLineSeries({ color: '#e6a23c', lineWidth: 1 })
  }
  if (panes.value.includes('RSI') && !rsiChart) { rsiChart = createChart(rsiEl.value, { height: 100 }); rsiSeries = rsiChart.addLineSeries({ color: '#9b59b6', lineWidth: 1 }) }
  if (panes.value.includes('KDJ') && !kdjChart) {
    kdjChart = createChart(kdjEl.value, { height: 100 })
    kSer = kdjChart.addLineSeries({ color: '#2e74b5', lineWidth: 1 }); dSer = kdjChart.addLineSeries({ color: '#e6a23c', lineWidth: 1 }); jSer = kdjChart.addLineSeries({ color: '#c0392b', lineWidth: 1 })
  }
  if (panes.value.includes('ATR') && !atrChart) { atrChart = createChart(atrEl.value, { height: 90 }); atrSeries = atrChart.addLineSeries({ color: '#16a085', lineWidth: 1 }) }
  if (panes.value.includes('CCI') && !cciChart) { cciChart = createChart(cciEl.value, { height: 90 }); cciSeries = cciChart.addLineSeries({ color: '#8e44ad', lineWidth: 1 }) }
  if (panes.value.includes('WR') && !wrChart) { wrChart = createChart(wrEl.value, { height: 90 }); wrSeries = wrChart.addLineSeries({ color: '#d35400', lineWidth: 1 }) }
  if (panes.value.includes('OBV') && !obvChart) { obvChart = createChart(obvEl.value, { height: 90 }); obvSeries = obvChart.addLineSeries({ color: '#2980b9', lineWidth: 1 }) }
  if (panes.value.includes('TRIX') && !trixChart) { trixChart = createChart(trixEl.value, { height: 90 }); trixSeries = trixChart.addLineSeries({ color: '#27ae60', lineWidth: 1 }) }
  if (panes.value.includes('ROC') && !rocChart) { rocChart = createChart(rocEl.value, { height: 90 }); rocSeries = rocChart.addLineSeries({ color: '#c0392b', lineWidth: 1 }) }
  if (panes.value.includes('CMF') && !cmfChart) { cmfChart = createChart(cmfEl.value, { height: 90 }); cmfSeries = cmfChart.addLineSeries({ color: '#16a085', lineWidth: 1 }) }
}

onMounted(() => {
  priceChart = createChart(priceEl.value, { height: 340, timeScale: { timeVisible: true } })
  candle = priceChart.addCandlestickSeries()
  applyTheme()
  priceChart.subscribeCrosshairMove(param => {
    const d = param.seriesData && param.seriesData.get(candle)
    updateLegend(d || null)
    if (param.time && showDec.value) {
      const hits = decisions.value.filter(x => barTime(x.ts) === param.time).slice(-3)
      if (hits.length) legend.value += '<br>' + hits.map(x => `<span style="color:${x.acted ? '#67c23a' : '#90a4ae'}">[${x.botId}] ${x.side || ''} ${x.outcome.replace(/</g, '&lt;')}</span>`).join('<br>')
    }
  })
  reload()
})
watch(() => theme.dark, () => applyTheme())
onUnmounted(() => { unobs.forEach(f => f()); [priceChart, volChart, macdChart, rsiChart, kdjChart, atrChart, cciChart, wrChart, obvChart, trixChart, rocChart, cmfChart].forEach(c => c && c.remove()) })

watch(lastPrice, (p) => {
  if (!p || !candle) return
  try { candle.update({ time: Math.floor(Date.now() / 1000), open: p, high: p, low: p, close: p }) } catch {}
})
</script>
<style scoped>
.px{display:inline-flex;align-items:center;gap:6px}
.replay{display:flex;align-items:center;gap:10px;margin-top:8px;flex-wrap:wrap;padding:6px 8px;border:1px solid var(--el-border-color-lighter);border-radius:6px}
.replay-body{display:flex;align-items:center;gap:8px;flex:1;flex-wrap:wrap;font-size:13px}.hint{color:#999;font-size:12px;margin-top:8px}</style>
