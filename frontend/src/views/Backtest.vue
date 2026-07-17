<template>
  <div>
    <el-card>
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>回測（來源：{{ source }}）</span>
          <div>
            <symbol-select v-model="btSymbol" size="small" style="width:130px;margin-right:8px" />
            <el-select v-model="btType" filterable size="small" style="width:130px;margin-right:8px">
              <el-option v-for="t in STRATEGY_TYPES" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
            <el-button :loading="loading" @click="runJava">Java 事件驅動</el-button>
            <el-button type="primary" :loading="loading" @click="runPython">Python 向量化</el-button>
            <el-button :loading="loading" @click="runOptimize">參數最佳化</el-button>
          </div>
        </div>
      </template>
      <el-row :gutter="16">
        <el-col :span="4" v-for="m in metricCards" :key="m.label">
          <div class="metric">
            <div class="m-label">{{ m.label }}</div>
            <div class="m-value">{{ m.value }}</div>
          </div>
        </el-col>
      </el-row>
      <el-alert v-if="optBest" :title="optBest" type="success" :closable="false" style="margin-top:10px" />
    </el-card>

    <el-card header="權益曲線與回撤" style="margin-top:16px">
      <div ref="chart" style="height:360px"></div>
      <div class="hint">Java：/api/backtest/run（事件驅動，與實盤共用策略介面）；Python：/research/backtest/run（向量化）。</div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { researchApi, backtestApi, marketApi } from '@/api'
import { STRATEGY_TYPES } from '@/config/strategies'
import { SYMBOLS } from '@/config/symbols'

const chart = ref(null)
const loading = ref(false)
const btType = ref('ma')
const btSymbol = ref('BTCUSDT')
const optBest = ref('')
const result = ref(null)
const source = ref('尚未執行')
let chartInst = null

const metricCards = computed(() => {
  const m = result.value?.metrics || {}
  const f = (v, isPct) => v == null ? '—' : (isPct ? (v * 100).toFixed(2) + '%' : v)
  return [
    { label: '年化報酬', value: f(m.annual_return, true) },
    { label: '總報酬', value: f(m.total_return, true) },
    { label: '最大回撤', value: f(m.max_drawdown, true) },
    { label: '夏普比率', value: f(m.sharpe, false) },
    { label: '期末權益', value: f(m.final_equity, false) }
  ]
})

async function realCloses() {
  try {
    const ks = (await marketApi.klines(btSymbol.value, '1m', 300)).data || []
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
  loading.value = true; optBest.value = ''
  try {
    const closes = await realCloses()
    const resp = await backtestApi.optimize({ type: btType.value, symbol: btSymbol.value, closes, grid: gridFor(btType.value) })
    const d = resp.data; const b = d.best
    optBest.value = `最佳參數（共 ${d.combinations} 組）：` + Object.entries(b).filter(([k]) => !['sharpe','total_return','max_drawdown','trades'].includes(k)).map(([k,v]) => k + '=' + v).join(', ') + ` ｜ Sharpe ${b.sharpe} · 交易 ${b.trades}`
    ElMessage.success('最佳化完成')
  } catch (e) { ElMessage.error('最佳化失敗') }
  finally { loading.value = false }
}
async function runJava() {
  loading.value = true
  try {
    const closes = await realCloses()
    const resp = await backtestApi.run({ symbol: btSymbol.value, closes, fast: 3, slow: 8, type: btType.value })
    result.value = resp.data
    source.value = 'Java 事件驅動'
    draw(); ElMessage.success('Java 回測完成')
  } catch (e) { ElMessage.error('Java 回測失敗（backtest-service 未啟動？）') }
  finally { loading.value = false }
}

async function runPython() {
  loading.value = true
  try {
    const prices = await realCloses()
    const sma = (a, i, w) => { const s = a.slice(Math.max(0, i - w + 1), i + 1); return s.reduce((x, y) => x + y, 0) / s.length }
    const signal = prices.map((_, i) => (i < 20 ? 0 : (sma(prices, i, 5) > sma(prices, i, 20) ? 1 : 0)))
    result.value = await researchApi.backtest({ prices, signal })
    source.value = 'Python 向量化'
    draw(); ElMessage.success('Python 回測完成')
  } catch (e) { ElMessage.error('Python 回測失敗（:8000 未啟動？）') }
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
    legend: { data: ['權益', '回撤'] },
    xAxis: { type: 'category', data: eq.map((_, i) => i) },
    yAxis: [{ type: 'value', scale: true }, { type: 'value', max: 0 }],
    series: [
      { name: '權益', type: 'line', smooth: true, showSymbol: false, data: eq },
      { name: '回撤', type: 'line', yAxisIndex: 1, showSymbol: false,
        areaStyle: { color: '#f5c0c0' }, lineStyle: { color: '#d9534f' }, data: dd }
    ]
  })
}
onMounted(() => { chartInst = echarts.init(chart.value) })
</script>

<style scoped>
.metric { text-align:center; padding:8px; }
.m-label { color:#888; font-size:13px; }
.m-value { font-size:22px; font-weight:700; color:#1f4e79; }
.hint { color:#999; font-size:12px; margin-top:8px; }
</style>
