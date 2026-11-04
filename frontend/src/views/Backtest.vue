<template>
  <div>
    <el-card>
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>{{ t('backtest.s6') }}{{ source }}）</span>
          <div>
            <symbol-select v-model="btSymbol" size="small" style="width:130px;margin-right:8px" />
            <el-select v-model="btInterval" size="small" style="width:90px;margin-right:8px" :title="t('backtest.s1')">
              <el-option v-for="i in ['1m','5m','15m','1h','4h','1d']" :key="i" :label="i" :value="i" />
            </el-select>
            <el-input-number v-model="btBars" :min="50" :max="1000" :step="100" size="small" style="width:120px;margin-right:8px" :title="t('backtest.s2')" />
            <el-select v-model="btMarket" size="small" style="width:120px;margin-right:8px">
              <el-option :label="t('backtest.mkSpot')" value="SPOT" />
              <el-option :label="t('backtest.mkFut')" value="USDT_M" />
            </el-select>
            <el-select v-model="btType" filterable size="small" style="width:130px;margin-right:8px">
              <el-option v-for="t in STRATEGY_TYPES" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
            <el-button :loading="loading" @click="runJava">{{ t('backtest.s7') }}</el-button>
            <el-button type="primary" :loading="loading" @click="runPython">{{ t('backtest.s8') }}</el-button>
            <el-button :loading="loading" @click="runOptimize">{{ t('backtest.s9') }}</el-button>
          </div>
        </div>
        <div class="parity">
          <el-checkbox v-model="applyRisk">{{ t('parity.apply') }}</el-checkbox>
          <template v-if="applyRisk">
            <el-select v-model="riskFrom" size="small" style="width:200px" :placeholder="t('parity.defaults')">
              <el-option :label="t('parity.defaults')" value="" />
              <el-option v-for="b in bots" :key="b.id" :label="t('parity.fromBot', { id: b.id })" :value="b.id" />
            </el-select>
            <span class="hint">{{ t('parity.sizing', { pct: notionalPct ? notionalPct.toFixed(1) : '95', eq: liveEquity ? liveEquity.toFixed(2) : '—' }) }}</span>
          </template>
        </div>
      </template>
      <div v-if="lastCosts || lastRisk" class="parity-result">
        <span v-if="lastCosts">{{ t('parity.costs', { fee: (lastCosts.feeRate * 100).toFixed(2), bps: lastCosts.slippageBps }) }}
          <el-tag size="small" :type="lastCosts.slippageSource === '實測' ? 'success' : 'info'" effect="plain">{{ lastCosts.slippageSource === '實測' ? t('parity.measured', { n: lastCosts.samples }) : t('parity.defaultSlip') }}</el-tag>
        </span>
        <template v-if="lastRisk && lastRisk.applied">
          <span>{{ t('parity.trips', { n: lastRisk.roundTrips, w: lastRisk.winRate ?? '—' }) }}</span>
          <span v-if="result?.shorts">{{ t('backtest.lsLine', { l: lastRisk.longTrips ?? 0, lp: lastRisk.longPnl ?? 0, s: lastRisk.shortTrips ?? 0, sp: lastRisk.shortPnl ?? 0 }) }}</span>
          <span v-if="Object.keys(lastRisk.exits || {}).length">{{ t('parity.exits') }}：<template v-for="(n, k) in lastRisk.exits" :key="k">{{ k }} {{ n }}　</template></span>
          <span v-if="Object.keys(lastRisk.blocked || {}).length" style="color:#e6a23c">{{ t('parity.blocked') }}：<template v-for="(n, k) in lastRisk.blocked" :key="k">{{ k }} {{ n }}　</template></span>
          <span v-if="lastRisk.pausedReason" style="color:#f56c6c">{{ t('parity.paused', { why: lastRisk.pausedReason }) }}</span>
        </template>
        <span v-else-if="lastRisk" class="hint">{{ t('parity.notApplied') }}</span>
      </div>
      <el-row :gutter="16">
        <el-col :span="4" v-for="m in metricCards" :key="m.label">
          <div class="metric">
            <div class="m-label">{{ m.label }}</div>
            <div class="m-value">{{ m.value }}</div>
          </div>
        </el-col>
      </el-row>
      <el-alert v-if="optBest" :title="optBest" :type="robust && robust.verdict !== 'ROBUST' ? 'warning' : 'success'" :closable="false" style="margin-top:10px">
        <div style="margin-top:8px;display:flex;align-items:center;gap:8px">
          <span>{{ t('backtest.s10') }}</span>
          <el-input v-model="botId" :placeholder="t('backtest.s3')" size="small" style="width:160px" />
          <el-input-number v-model="botQty" :step="0.001" :min="0.0001" size="small" style="width:130px" :title="t('backtest.s4')" />
          <el-button size="small" type="primary" :loading="creating" @click="createFromBest">{{ t('backtest.s11') }}</el-button>
          <span class="hint" v-if="bestParams">{{ btType }} · {{ btSymbol }} · {{ Object.entries(bestParams).map(([k, v]) => k + '=' + v).join(', ') }}</span>
        </div>
      </el-alert>
      <div v-if="robust" class="robust">
        <div class="robust-head">
          <b>{{ t('robust.title') }}</b>
          <el-tag :type="VERDICT_TYPE[robust.verdict] || 'info'" effect="dark">{{ t('robust.v.' + robust.verdict) }}</el-tag>
          <span class="hint">{{ t('robust.trials', { n: robust.trials, s: robust.splits || 0 }) }}</span>
        </div>
        <el-row :gutter="12" style="margin-top:8px">
          <el-col :xs="12" :sm="6"><div class="metric"><div class="m-label">PBO {{ t('robust.pboL') }}</div><div class="m-value" :style="{ color: robust.pbo > 0.5 ? '#f56c6c' : robust.pbo > 0.3 ? '#e6a23c' : '#67c23a' }">{{ robust.pbo == null ? '—' : Math.round(robust.pbo * 100) + '%' }}</div></div></el-col>
          <el-col :xs="12" :sm="6"><div class="metric"><div class="m-label">DSR {{ t('robust.dsrL') }}</div><div class="m-value" :style="{ color: robust.dsr < 0.5 ? '#f56c6c' : robust.dsr < 0.9 ? '#e6a23c' : '#67c23a' }">{{ Math.round((robust.dsr || 0) * 100) }}%</div></div></el-col>
          <el-col :xs="12" :sm="6"><div class="metric"><div class="m-label">{{ t('robust.hurdle') }}</div><div class="m-value">{{ robust.bestSharpe }} / {{ robust.sharpeHurdle }}</div></div></el-col>
          <el-col :xs="12" :sm="6"><div class="metric"><div class="m-label">{{ t('robust.wfEff') }}</div><div class="m-value" :style="{ color: (robust.walkForward?.efficiency || 0) < 0.5 ? '#e6a23c' : '#67c23a' }">{{ Math.round((robust.walkForward?.efficiency || 0) * 100) }}%</div></div></el-col>
        </el-row>
        <ul class="reasons"><li v-for="r in robust.reasons" :key="r">{{ r }}</li></ul>
        <el-table v-if="robust.walkForward?.folds" :data="robust.walkForward.folds" size="small" style="margin-top:6px">
          <el-table-column prop="fold" :label="t('robust.fold')" width="70" />
          <el-table-column :label="t('robust.picked')"><template #default="{ row }">{{ Object.entries(row.params || {}).map(([k, v]) => k + '=' + v).join(', ') }}</template></el-table-column>
          <el-table-column prop="isSharpe" :label="t('robust.isS')" width="130" />
          <el-table-column :label="t('robust.oosS')" width="130"><template #default="{ row }"><span :style="{ color: row.oosSharpe > 0 ? '#67c23a' : '#f56c6c' }">{{ row.oosSharpe }}</span></template></el-table-column>
        </el-table>
        <div v-if="robust.recommended" style="margin-top:10px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">
          <span>{{ t('robust.rec') }}</span>
          <b>{{ Object.entries(paramsOnly(robust.recommended)).map(([k, v]) => k + '=' + v).join(', ') }}</b>
          <span class="hint">{{ t('robust.recOos', { s: robust.recommendedOosSharpe }) }}</span>
          <el-button size="small" type="success" :loading="creating" @click="createFrom(paramsOnly(robust.recommended))">{{ t('robust.useRec') }}</el-button>
        </div>
        <div class="hint" style="margin-top:6px;line-height:1.6">{{ t('robust.hint') }}</div>
      </div>
    </el-card>

    <el-card :header="t('backtest.s5')" style="margin-top:16px">
      <div ref="chart" style="height:360px"></div>
      <div class="hint">{{ t('backtest.s12') }}</div>
    </el-card>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { observeWidth } from '@/utils/resize'
let unobsChart = () => {}
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { researchApi, backtestApi, marketApi, strategyApi, accountApi } from '@/api'
import { STRATEGY_TYPES } from '@/config/strategies'
import { SYMBOLS } from '@/config/symbols'

const chart = ref(null)
const loading = ref(false)
const btType = ref('ma')
const btSymbol = ref('BTCUSDT')
const btInterval = ref('1m')   // 後端已支援任意 interval / 根數（PR #13），以前前端寫死 1m × 300
const btBars = ref(300)
const optBest = ref('')
const bestParams = ref(null)
const botId = ref(''); const botQty = ref(0.001); const creating = ref(false)
const robust = ref(null)
const VERDICT_TYPE = { ROBUST: 'success', SUSPECT: 'warning', OVERFIT: 'danger', INSUFFICIENT: 'info' }
const paramsOnly = row => Object.fromEntries(Object.entries(row || {}).filter(([k]) => !['sharpe', 'total_return', 'max_drawdown', 'trades', 'is_sharpe', 'oos_sharpe'].includes(k)))
async function createFrom(p) { bestParams.value = p; return createFromBest() }
async function createFromBest() {
  if (!bestParams.value) return
  const id = (botId.value || `${btType.value}-${btSymbol.value.toLowerCase().replace('usdt', '')}-${Date.now().toString().slice(-4)}`).trim()
  creating.value = true
  try {
    const r = await strategyApi.create({ id, type: btType.value, symbol: btSymbol.value, params: { ...bestParams.value, qty: botQty.value } })
    if (r.code && r.code !== 0) throw new Error(r.message)
    ElMessage.success(t('backtest.s31', { id })); botId.value = ''
  } catch (e) { ElMessage.error(t('backtest.s13') + (e?.message || '')) }
  finally { creating.value = false }
}
const result = ref(null)
const source = ref(t('backtest.s14'))
let chartInst = null

// ── 與實盤一致：同一套風控、同樣的下單比例、實測成本 ──
const applyRisk = ref(true), riskFrom = ref(''), bots = ref([])
const btMarket = ref('SPOT')
// 從某個合約機器人帶風控設定 → 市場跟著切成合約
watch(riskFrom, id => { const b = bots.value.find(x => x.id === id); const m = b?.params?.market || b?.market; if (m) btMarket.value = String(m).toUpperCase() === 'USDT_M' ? 'USDT_M' : 'SPOT' })
const liveNotional = ref(0), liveEquity = ref(0)
const notionalPct = computed(() => liveNotional.value > 0 && liveEquity.value > 0 ? liveNotional.value / liveEquity.value * 100 : 0)
const lastCosts = ref(null), lastRisk = ref(null)
async function loadParity() {
  try { bots.value = (await strategyApi.list()).data || [] } catch {}
  try { liveNotional.value = Number((await strategyApi.getOrderNotional()).data) || 0 } catch {}
  try { liveEquity.value = Number((await accountApi.balance()).data?.totalUsdt) || 0 } catch {}
}
function parityBody() {
  if (!applyRisk.value) return { applyRisk: false }
  const b = bots.value.find(x => x.id === riskFrom.value)
  return { applyRisk: true, risk: b?.risk?.params || undefined, notionalPct: notionalPct.value, liveEquity: liveEquity.value }
}
const metricCards = computed(() => {
  const m = result.value?.metrics || {}
  const f = (v, isPct) => v == null ? '—' : (isPct ? (v * 100).toFixed(2) + '%' : v)
  return [
    { label: t('backtest.s15'), value: f(m.annual_return, true) },
    { label: t('backtest.s16'), value: f(m.total_return, true) },
    { label: t('backtest.s17'), value: f(m.max_drawdown, true) },
    { label: t('backtest.s18'), value: f(m.sharpe, false) },
    { label: t('backtest.s19'), value: f(m.final_equity, false) }
  ]
})

async function realCloses() {
  try {
    const ks = (await marketApi.klines(btSymbol.value, btInterval.value, btBars.value)).data || []
    if (ks.length >= 30) return ks.map(k => k.close)
  } catch {}
  return samplePrices()
}
function samplePrices(n = 200) {
  const prices = []; let p = 100
  for (let i = 0; i < n; i++) { p += (Math.random() - 0.48) * 2; prices.push(+p.toFixed(2)) }
  return prices
}

function gridFor(type) {
  if (['ma','ema','wma','tema'].includes(type)) return { fast: [3,5,10], slow: [15,20,30] }
  if (['macd'].includes(type)) return { fast: [8,12], slow: [21,26], signal: [9] }
  if (['boll','bollbreak','keltner','atr'].includes(type)) return { period: [14,20,30], mult: [1.5,2,2.5] }
  if (['dualthrust'].includes(type)) return { period: [3,5,8], k: [0.3,0.5,0.7] }
  return { period: [9,14,20,25] }   // 擺盪類
}
async function runOptimize() {
  loading.value = true; optBest.value = ''; robust.value = null
  try {
    const resp = await backtestApi.optimize({ type: btType.value, symbol: btSymbol.value, interval: btInterval.value, bars: btBars.value, grid: gridFor(btType.value), ...parityBody() })
    lastCosts.value = resp.data?.costs || null; lastRisk.value = resp.data?.riskApplied === false ? { applied: false } : null
    const d = resp.data; const b = d.best
    bestParams.value = paramsOnly(b)
    robust.value = d.robustness || null
    optBest.value = t('backtest.s32', { n: d.combinations }) + Object.entries(paramsOnly(b)).map(([k,v]) => k + '=' + v).join(', ') + ` ｜ Sharpe ${b.sharpe} · 交易 ${b.trades}`
    ElMessage.success(t('backtest.s20'))
  } catch (e) { ElMessage.error(t('backtest.s21')) }
  finally { loading.value = false }
}
async function runJava() {
  loading.value = true
  try {
    // 後端直接用 market-data 的真實 OHLCV（TimescaleDB），不再由前端抓收盤價送過去
    const resp = await backtestApi.run({ symbol: btSymbol.value, interval: btInterval.value, bars: btBars.value, fast: 3, slow: 8, type: btType.value, market: btMarket.value, ...parityBody() })
    lastCosts.value = resp.data?.costs || null; lastRisk.value = resp.data?.risk || null
    if (resp.code && resp.code !== 0) throw new Error(resp.message)
    result.value = resp.data
    source.value = t('backtest.s22') + (resp.data.dataSource || '') + ' · ' + resp.data.bars + t('backtest.s23')
    draw(); ElMessage.success(t('backtest.s24'))
  } catch (e) { ElMessage.error(t('backtest.s25') + (e?.message || t('backtest.s26'))) }
  finally { loading.value = false }
}

async function runPython() {
  loading.value = true
  try {
    const prices = await realCloses()
    const sma = (a, i, w) => { const s = a.slice(Math.max(0, i - w + 1), i + 1); return s.reduce((x, y) => x + y, 0) / s.length }
    const signal = prices.map((_, i) => (i < 20 ? 0 : (sma(prices, i, 5) > sma(prices, i, 20) ? 1 : 0)))
    result.value = await researchApi.backtest({ prices, signal })
    source.value = t('backtest.s8')
    draw(); ElMessage.success(t('backtest.s27'))
  } catch (e) { ElMessage.error(t('backtest.s28')) }
  finally { loading.value = false }
}

function draw() {
  if (!chartInst) chartInst = echarts.init(chart.value)
  const eq = result.value.equity || []
  // 若回應未附回撤序列（Java），由權益曲線計算
  let dd = result.value.drawdown
  if (!dd) { let peak = -Infinity; dd = eq.map(v => { peak = Math.max(peak, v); return +(v / peak - 1).toFixed(4) }) }
  chartInst.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: [t('backtest.s29'), t('backtest.s30')] },
    xAxis: { type: 'category', data: eq.map((_, i) => i) },
    yAxis: [{ type: 'value', scale: true }, { type: 'value', max: 0 }],
    series: [
      { name: t('backtest.s29'), type: 'line', smooth: true, showSymbol: false, data: eq },
      { name: t('backtest.s30'), type: 'line', yAxisIndex: 1, showSymbol: false,
        areaStyle: { color: '#f5c0c0' }, lineStyle: { color: '#d9534f' }, data: dd }
    ]
  })
}
onMounted(() => { chartInst = echarts.init(chart.value); unobsChart = observeWidth(chart.value, () => chartInst?.resize()); loadParity() })
onUnmounted(() => { unobsChart(); chartInst?.dispose(); chartInst = null })
</script>

<style scoped>
.metric { text-align:center; padding:8px; }
.m-label { color:#888; font-size:13px; }
.m-value { font-size:22px; font-weight:700; color:#1f4e79; }
.hint { color:#999; font-size:12px; margin-top:8px; }
.robust{margin-top:12px;padding:12px;border:1px solid var(--el-border-color-lighter);border-radius:8px}
.robust-head{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.reasons{margin:8px 0 0;padding-left:18px;font-size:13px;line-height:1.7}
/* parity */
.parity{display:flex;align-items:center;gap:10px;margin-top:8px;flex-wrap:wrap}
.parity-result{display:flex;gap:14px;flex-wrap:wrap;align-items:center;font-size:13px;margin-bottom:10px}
</style>
