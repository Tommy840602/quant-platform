<template>
  <div>
    <el-card>
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>{{ t('strategy.s13') }}</span>
          <div>
            <span class="hint" style="margin-right:6px">{{ t('pipeline.auto') }}</span>
            <el-switch v-model="pipeline.auto" size="small" style="margin-right:12px" @change="togglePipeline" />
            <el-button size="small" @click="testSlack">{{ t('strategy.s14') }}</el-button>
            <el-button size="small" type="primary" @click="load">{{ t('strategy.s15') }}</el-button>
          </div>
        </div>
      </template>
      <el-alert v-if="sizingWarn" type="error" :closable="false" show-icon style="margin-bottom:12px">
        <template #title>{{ t('sizing.title') }}</template>
        <div style="line-height:1.7">
          {{ sizingText(sizingWarn.p) }}<br>
          <b>{{ t('sizing.fix', { min: MIN_NOTIONAL }) }}</b>
          <el-button type="danger" size="small" style="margin-left:10px" @click="$router.push('/risk')">{{ t('sizing.go') }}</el-button>
        </div>
      </el-alert>
      <el-table :data="list" v-loading="loading">
        <el-table-column prop="id" label="ID" width="140" />
        <el-table-column prop="name" :label="t('strategy.s1')">
          <template #default="{ row }">
            {{ row.name }}
            <el-tag size="small" effect="plain" type="info" style="margin-left:4px">{{ row.diag?.interval || row.params?.interval || '1m' }}</el-tag>
            <el-tag v-if="row.family && row.family !== 'any'" size="small" :type="row.family === 'trend' ? 'success' : 'primary'" effect="plain" style="margin-left:4px">{{ t('regime.fam.' + row.family) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('strategy.s2')" width="100">
        <template #default="{ row }">
          <span style="display:inline-flex;align-items:center;gap:6px"><coin-icon v-if="row.symbol && row.symbol !== 'ALL'" :symbol="row.symbol" :size="16" />{{ row.symbol }}</span>
        </template>
      </el-table-column>
        <el-table-column prop="position" :label="t('strategy.s3')" width="90" />
        <el-table-column :label="t('strategy.s4')" width="120">
          <template #default="{ row }">
            <span v-if="row.status === 'RUNNING'" :title="row.startedAt ? t('strategy.s44') + locale.fmtTime(row.startedAt) : ''">{{ fmtDur(row.runningSec, fetchedAt) }}</span>
            <span v-else class="hint">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('strategy.s5')" width="340">
          <template #default="{ row }">
            <template v-if="row.pnl">
              <div style="display:flex;align-items:center;gap:6px">
                <b :style="{ color: fmtPnl(row.pnl).color }">{{ fmtPnl(row.pnl).word }} {{ fmtPnl(row.pnl).text }}</b>
                <svg v-if="spark(row.id)" :width="spark(row.id).w" :height="spark(row.id).hh" style="vertical-align:middle">
                  <line :x1="0" :x2="spark(row.id).w" :y1="spark(row.id).zero" :y2="spark(row.id).zero" stroke="#999" stroke-dasharray="2,2" />
                  <polyline :points="spark(row.id).pts" fill="none" :stroke="spark(row.id).color" stroke-width="1.5" />
                </svg>
              </div>
              <div class="hint">{{ t('common.realized') }} {{ num(row.pnl.realized) }} · {{ t('common.unrealized') }} {{ num(row.pnl.unrealized) }} · {{ t('common.fees') }} {{ Number(row.pnl.fees || 0).toFixed(2) }} · {{ t('common.trades') }} {{ row.pnl.trades }}<template v-if="row.pnl.openQty"> · {{ t('strategy.s50').trim() }} {{ row.pnl.openQty }} @ {{ row.pnl.avgCost }}</template></div>
            </template>
            <span v-else class="hint">{{ row.status === 'RUNNING' ? t('strategy.s43') : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" :label="t('strategy.s6')" width="300">
          <template #default="{ row }">
            <el-dropdown trigger="click" @command="st => changeStage(row, st)" style="margin-right:6px;vertical-align:middle">
              <el-tag :type="STAGE_TYPE[row.stage || 'LIVE']" effect="dark" style="cursor:pointer">{{ t('pipeline.st.' + (row.stage || 'LIVE')) }} ▾</el-tag>
              <template #dropdown><el-dropdown-menu>
                <el-dropdown-item v-for="st in ['PAPER', 'PILOT', 'LIVE']" :key="st" :command="st" :disabled="st === (row.stage || 'LIVE')">{{ t('pipeline.st.' + st) }}</el-dropdown-item>
              </el-dropdown-menu></template>
            </el-dropdown>
            <el-tag :type="row.status === 'RUNNING' ? (row.risk?.paused ? 'warning' : 'success') : 'info'">{{ row.status === 'RUNNING' && row.risk?.paused ? t('botRisk.paused') : row.status }}</el-tag>
            <el-button v-if="row.status === 'RUNNING' && row.risk?.paused" size="small" type="warning" link style="margin-left:6px" @click="resume(row.id)">{{ t('botRisk.resume') }}</el-button>
            <div v-if="row.status === 'RUNNING' && row.risk?.paused" style="color:#e6a23c;font-size:12px">{{ row.risk.paused }}</div>
            <div v-if="row.status === 'RUNNING' && row.diag" class="hint" style="line-height:1.5;margin-top:4px">
              <div :title="row.diag.lastBarAt ? t('strategy.diagLastBar') + locale.fmtTime(row.diag.lastBarAt) : ''">
                {{ t('strategy.diagBars', { n: row.diag.closedBars, syms: row.diag.symbols }) }}
                <span v-if="!row.diag.closedBars" style="color:#e6a23c">· {{ t('strategy.diagNoBars') }}</span>
                <span v-if="row.diag.warmed" class="hint">· {{ t('interval.warmed', { n: row.diag.warmed }) }}</span>
                <span v-else-if="row.diag.closedBars < (row.params?.slow || 8) * row.diag.symbols" style="color:#e6a23c">· {{ t('strategy.diagWarmup', { n: row.params?.slow || 8 }) }}</span>
              </div>
              <div v-if="row.gates && row.gates.checks?.length" :title="t('pipeline.gateTip')">
                {{ row.gates.next ? t('pipeline.toNext', { next: t('pipeline.st.' + row.gates.next) }) : t('pipeline.keep') }}
                <span v-for="c in row.gates.checks" :key="c.key" :style="{ color: c.ok ? '#67c23a' : '#e6a23c', marginRight: '6px' }">{{ t('pipeline.ck.' + c.key) }} {{ c.value }}/{{ c.need }}{{ c.ok ? '✓' : '' }}</span>
              </div>
              <div v-if="row.shadow" :style="{ color: row.shadow.gap < -1 ? '#f56c6c' : '' }">
                {{ t('pipeline.shadow', { sh: num(row.shadow.shadowPnl), live: num(row.shadow.livePnl), gap: num(row.shadow.gap), slip: row.shadow.slipBps, rej: row.shadow.rejects }) }}
              </div>
              <div v-if="row.risk">{{ t('botRisk.line', { open: row.risk.openSlots, max: row.risk.params.maxPositions, loss: row.risk.consecLosses, pnl: num(row.risk.sessionPnl) }) }}</div>
              <div v-for="x in row.risk?.exits || []" :key="x.symbol" style="color:var(--el-text-color-regular)">
                {{ x.symbol }} {{ +x.qty.toPrecision(4) }} @{{ +x.entry.toPrecision(6) }} → {{ t('botRisk.stop') }} {{ x.stop ? +x.stop.toPrecision(6) : '—' }}<template v-if="x.trail"> · {{ t('botRisk.trail') }} {{ +x.trail.toPrecision(6) }}</template><template v-if="x.takeProfit"> · {{ t('botRisk.tp') }} {{ +x.takeProfit.toPrecision(6) }}</template>
              </div>
              <div :title="row.diag.lastSignalAt ? locale.fmtTime(row.diag.lastSignalAt) : ''">
                {{ t('strategy.diagSignals', { n: row.diag.signals }) }}<template v-if="row.diag.lastSignal"> · {{ row.diag.lastSignal }} → {{ row.diag.lastOutcome }}</template>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('strategy.s7')" width="340">
          <template #default="{ row }">
            <el-button size="small" type="success" :disabled="row.status === 'RUNNING'" @click="start(row.id)">{{ t('strategy.s17') }}</el-button>
            <el-dropdown split-button size="small" type="warning" :disabled="row.status !== 'RUNNING'" @click="stop(row.id, false)" @command="c => stop(row.id, c === 'flatten')" style="margin:0 6px">
              {{ t('strategy.s18') }}
              <template #dropdown><el-dropdown-menu>
                <el-dropdown-item command="flatten">{{ t('strategy.s19') }}</el-dropdown-item>
              </el-dropdown-menu></template>
            </el-dropdown>
            <el-button size="small" @click="openEdit(row)">{{ t('strategy.s20') }}</el-button>
            <el-button size="small" @click="openLog(row)">{{ t('decisions.btn') }}</el-button>
            <el-button size="small" type="danger" plain :disabled="row.builtin" :title="row.builtin ? t('strategy.s51') : ''" @click="removeBot(row)">{{ t('strategy.s21') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card style="margin-top:16px">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px">
          <span>{{ t('regime.title') }}</span>
          <span class="hint">{{ t('regime.legend') }}</span>
        </div>
      </template>
      <div style="display:flex;gap:24px;flex-wrap:wrap">
        <div style="flex:1;min-width:320px">
          <div style="margin-bottom:8px">
            <el-tag type="success" style="margin-right:6px">{{ t('regime.TREND') }} {{ regimeCount.TREND }}</el-tag>
            <el-tag type="primary" style="margin-right:6px">{{ t('regime.RANGE') }} {{ regimeCount.RANGE }}</el-tag>
            <el-tag type="info" style="margin-right:6px">{{ t('regime.MIXED') }} {{ regimeCount.MIXED }}</el-tag>
            <el-tag v-if="regimeCount.HIGHVOL" type="danger" style="margin-right:6px">{{ t('regime.highVol') }} {{ regimeCount.HIGHVOL }}</el-tag>
            <el-tag v-if="regimeCount.UNKNOWN" type="info" effect="plain">{{ t('regime.UNKNOWN') }} {{ regimeCount.UNKNOWN }}</el-tag>
          </div>
          <div style="display:flex;flex-wrap:wrap;gap:6px">
            <el-tooltip v-for="(r, sym) in regimes" :key="sym" :content="'ADX ' + (r.adx ?? '—') + ' · ' + t('regime.vol') + ' ' + r.volRatio + '×'" placement="top">
              <el-tag size="small" :type="r.highVol ? 'danger' : r.regime === 'TREND' ? 'success' : r.regime === 'RANGE' ? 'primary' : 'info'" :effect="r.regime === 'UNKNOWN' ? 'plain' : 'light'">{{ sym.replace('USDT', '') }}</el-tag>
            </el-tooltip>
          </div>
        </div>
        <div style="flex:1;min-width:360px">
          <div style="display:flex;align-items:center;gap:10px;margin-bottom:8px;flex-wrap:wrap">
            <b>{{ t('bandit.title') }}</b>
            <el-switch v-model="bandit.enabled" @change="toggleBandit" />
            <el-button size="small" :disabled="!bandit.enabled" @click="rebalance">{{ t('bandit.rebalance') }}</el-button>
            <span class="hint">{{ t('bandit.range', { lo: (+bandit.floor || 0).toFixed(2), hi: bandit.cap }) }}<template v-if="bandit.lastRunAt"> · {{ locale.fmtTime(bandit.lastRunAt, { year: undefined }) }}</template></span>
          </div>
          <el-table :data="bandit.bots || []" size="small" :empty-text="t('bandit.empty')">
            <el-table-column prop="name" :label="t('bandit.bot')" min-width="130" />
            <el-table-column prop="trades" :label="t('bandit.trades')" width="80" />
            <el-table-column :label="t('bandit.mean')" width="110"><template #default="{ row }"><span :style="{ color: row.meanPct > 0 ? '#67c23a' : row.meanPct < 0 ? '#f56c6c' : '' }">{{ row.meanPct > 0 ? '+' : '' }}{{ row.meanPct }}%</span></template></el-table-column>
            <el-table-column :label="t('bandit.pBest')" width="140"><template #default="{ row }"><el-progress :percentage="Math.round(row.pBest * 100)" :stroke-width="8" /></template></el-table-column>
            <el-table-column :label="t('bandit.alloc')" width="90"><template #default="{ row }"><b>×{{ (+row.allocation).toFixed(2) }}</b></template></el-table-column>
          </el-table>
          <div class="hint" style="margin-top:6px;line-height:1.6">{{ t('bandit.hint') }}</div>
        </div>
      </div>
    </el-card>

    <el-card :header="t('strategy.s8')" style="margin-top:16px">
      <el-form :inline="true">
        <el-form-item label="ID"><el-input v-model="bot.id" placeholder="my-bot" style="width:130px" /></el-form-item>
        <el-form-item :label="t('strategy.s9')">
          <el-select v-model="bot.type" filterable style="width:150px">
            <el-option v-for="t in STRATEGY_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('strategy.s2')">
          <symbol-select v-model="bot.symbol" allow-all :all-label="t('strategy.allSymbols')" style="width:190px" />
        </el-form-item>
        <el-form-item :label="t('interval.label')">
          <el-select v-model="bot.interval" style="width:90px"><el-option v-for="i in ['1m', '5m', '15m', '1h']" :key="i" :label="i" :value="i" /></el-select>
        </el-form-item>
        <el-form-item :label="t('strategy.s10')"><el-input-number v-model="bot.qty" :step="0.001" :min="0.001" /></el-form-item>
        <el-form-item><el-button type="primary" @click="createBot">{{ t('strategy.s22') }}</el-button></el-form-item>
      </el-form>
      <div class="hint">{{ t('strategy.s23') }} {{ t('pipeline.newBot') }}</div>
    </el-card>

    <el-dialog v-model="edit.show" :title="t('strategy.s52') + edit.id" width="560px">
      <el-divider content-position="left">{{ t('botRisk.section') }}</el-divider>
      <el-form label-width="170px">
        <el-form-item v-for="k in RISK_KEYS" :key="k" :label="t('botRisk.k.' + k)">
 <el-switch v-if="k === 'regimeFilter' || k === 'blockHighVol' || k === 'execMaker'" :model-value="edit.params[k] > 0" @update:model-value="v => edit.params[k] = v ? 1 : 0" style="width:150px" />
 <el-input-number v-else v-model="edit.params[k]" :min="0" :step="k.endsWith('Atr') || k === 'riskPerTradePct' ? 0.5 : 1" :precision="k.endsWith('Atr') || k === 'takeProfitPct' || k === 'riskPerTradePct' ? 1 : 0" style="width:150px" />
          <span class="hint" style="margin-left:8px">{{ t('botRisk.h.' + k) }}</span>
        </el-form-item>
      </el-form>
      <el-divider content-position="left">{{ t('botRisk.strategySection') }}</el-divider>
      <el-form label-width="120px">
        <el-form-item :label="t('interval.label')">
          <el-select v-model="edit.params.interval" style="width:120px"><el-option v-for="i in ['1m', '5m', '15m', '1h']" :key="i" :label="i" :value="i" /></el-select>
        </el-form-item>
        <el-form-item v-for="k in edit.keys.filter(k => !RISK_KEYS.includes(k) && k !== 'interval' && k !== 'stage')" :key="k" :label="k">
          <el-input-number v-model="edit.params[k]" :step="stepFor(k, edit.params[k])" :precision="precisionFor(edit.params[k])" style="width:200px" />
        </el-form-item>
        <el-form-item :label="t('strategy.s11')">
          <el-input v-model="edit.newKey" :placeholder="t('strategy.s12')" style="width:120px;margin-right:6px" />
          <el-input-number v-model="edit.newVal" style="width:140px" />
          <el-button size="small" @click="addKey" style="margin-left:6px">{{ t('strategy.s24') }}</el-button>
        </el-form-item>
      </el-form>
      <div class="hint">{{ t('strategy.s25') }}</div>
      <template #footer>
        <el-button @click="edit.show = false">{{ t('strategy.s26') }}</el-button>
        <el-button type="primary" :loading="edit.saving" @click="saveEdit">{{ t('strategy.s27') }}</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="dlog.show" :title="t('decisions.title') + ' · ' + dlog.id" size="min(860px, 96vw)">
      <div style="display:flex;gap:8px;align-items:center;margin-bottom:10px;flex-wrap:wrap">
        <symbol-select v-model="dlog.symbol" clearable :placeholder="t('decisions.allSymbols')" style="width:170px" @change="loadLog" />
        <el-checkbox v-model="dlog.acted" @change="loadLog">{{ t('decisions.actedOnly') }}</el-checkbox>
        <el-button size="small" @click="loadLog">{{ t('strategy.s15') }}</el-button>
      </div>
      <el-table :data="dlog.rows" size="small" v-loading="dlog.loading" :empty-text="t('decisions.empty')" height="calc(100vh - 170px)">
        <el-table-column :label="t('orders.time')" width="140"><template #default="{ row }">{{ locale.fmtTime(row.ts, { year: undefined }) }}</template></el-table-column>
        <el-table-column prop="symbol" :label="t('strategy.s2')" width="110" />
        <el-table-column :label="t('live.s4')" width="64"><template #default="{ row }"><span :style="{ color: row.side === 'BUY' ? '#26a69a' : '#ef5350' }">{{ row.side }}</span></template></el-table-column>
        <el-table-column :label="t('live.s30')" width="100"><template #default="{ row }">{{ row.price ? +row.price.toPrecision(6) : '' }}</template></el-table-column>
        <el-table-column :label="t('decisions.outcome')" min-width="240">
          <template #default="{ row }"><span :style="{ color: row.acted ? '#67c23a' : 'var(--el-text-color-secondary)', fontWeight: row.acted ? 600 : 400 }">{{ row.outcome }}</span></template>
        </el-table-column>
        <el-table-column :label="t('decisions.market')" width="150">
          <template #default="{ row }"><span class="hint">{{ row.detail?.regime ? t('regime.' + row.detail.regime) : '' }}<template v-if="row.detail?.adx != null"> · ADX {{ row.detail.adx }}</template></span></template>
        </el-table-column>
        <el-table-column width="80"><template #default="{ row }"><el-button link type="primary" size="small" @click="toChart(row)">{{ t('decisions.onChart') }}</el-button></template></el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
import { useFmt } from '@/composables/useFmt'
const { t } = useI18n()
// 執行時長 / 收益輔助
const { fmtDur, fmtPnl, num } = useFmt()
const fetchedAt = ref(0)
const sign = v => (v > 0 ? '+' : '') + Number(v || 0).toFixed(2)
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { strategyApi, notifyApi } from '@/api'
import { useRouter } from 'vue-router'
import { useMarketStore } from '@/store/market'
import { sizingProblem, MIN_NOTIONAL } from '@/composables/useSizingCheck'
import { STRATEGY_TYPES } from '@/config/strategies'
import { useLocaleStore } from '@/store/locale'
const locale = useLocaleStore()
import { SYMBOLS } from '@/config/symbols'

const list = ref([]); const loading = ref(false)
const bot = reactive({ id: '', type: 'rsi', symbol: 'BTCUSDT', qty: 0.001, interval: '15m' })

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    list.value = (await strategyApi.list()).data || []
    fetchedAt.value = Date.now()
    // 收益走勢：每次刷新把各機器人 pnl 推進記憶體序列（最多 120 點 ≈ 30 分鐘）
    for (const b of list.value) {
      if (!b.pnl) { delete pnlHist[b.id]; continue }
      const h = (pnlHist[b.id] ||= []); h.push(+b.pnl.pnl || 0); if (h.length > 120) h.shift()
    }
  }
  catch { if (!silent) ElMessage.error(t('strategy.s28')) }
  finally { if (!silent) loading.value = false }
}
const pnlHist = reactive({})
/** 小型走勢（SVG polyline）：以 0 為基準，上綠下紅 */
function spark(id) {
  const h = pnlHist[id]; if (!h || h.length < 2) return null
  const w = 80, hh = 22, min = Math.min(0, ...h), max = Math.max(0, ...h), span = (max - min) || 1
  const pts = h.map((v, i) => `${(i / (h.length - 1) * w).toFixed(1)},${(hh - (v - min) / span * hh).toFixed(1)}`).join(' ')
  const zero = (hh - (0 - min) / span * hh).toFixed(1)
  return { pts, zero, w, hh, color: h[h.length - 1] >= 0 ? '#67c23a' : '#f56c6c' }
}
const router = useRouter()
const market = useMarketStore()
const orderNotional = ref(0)
async function loadNotional() { try { orderNotional.value = Number((await strategyApi.getOrderNotional()).data) || 0 } catch {} }
const sizingText = p => p.kind === 'notional'
  ? t('sizing.notional', { n: p.notional, min: MIN_NOTIONAL })
  : t('sizing.fixed', { qty: p.qty, sym: p.symbol, v: p.value < 0.01 ? p.value.toFixed(4) : p.value.toFixed(2), min: MIN_NOTIONAL })
/** 運行中的機器人有任何一個「下單一定被幣安拒絕」→ 頁面頂端紅色警告 */
const sizingWarn = computed(() => {
  for (const b of list.value) {
    if (b.status !== 'RUNNING') continue
    const p = sizingProblem(b, orderNotional.value, market.prices)
    if (p) return { id: b.id, p }
  }
  return null
})
async function resume(id) { await strategyApi.resume(id); ElMessage.success(t('botRisk.resumed')); load() }
async function start(id) {
  await loadNotional()
  const bot = list.value.find(b => b.id === id)
  const p = sizingProblem(bot, orderNotional.value, market.prices)
  if (p) {
    try {
      await ElMessageBox.confirm(sizingText(p) + '\n\n' + t('sizing.fix', { min: MIN_NOTIONAL }), t('sizing.title'),
        { type: 'error', confirmButtonText: t('sizing.go'), cancelButtonText: t('sizing.anyway'), distinguishCancelAndClose: true })
      router.push('/risk'); return
    } catch (e) { if (e === 'close') return }
  }
  await strategyApi.start(id); ElMessage.success(t('strategy.s29')); load()
}
async function stop(id, flatten) {
  if (flatten) {
    try { await ElMessageBox.confirm(t('strategy.s53', { id }), t('strategy.s30'), { type: 'warning' }) } catch { return }
  }
  const r = await strategyApi.stop(id, flatten); ElMessage.success(r.data || t('strategy.s31')); load()
}
async function removeBot(row) {
  try { await ElMessageBox.confirm(t('strategy.s54', { id: row.id }) + (row.status === 'RUNNING' ? t('strategy.s32') : ''), t('strategy.s21'), { type: 'warning', confirmButtonText: t('strategy.s21'), cancelButtonText: t('strategy.s26') }) }
  catch { return }
  let flatten = false
  if (row.position > 0) {
    try {
      await ElMessageBox.confirm(t('strategy.s55', { pos: row.position }), t('strategy.s33'), { type: 'warning', confirmButtonText: t('strategy.s33'), cancelButtonText: t('strategy.s34'), distinguishCancelAndClose: true })
      flatten = true
    } catch (e) { if (e === 'close') return }   // 關閉視窗 = 放棄；「不平倉」繼續
  }
  const r = await strategyApi.remove(row.id, flatten)
  if (r.code && r.code !== 0) return ElMessage.error(r.message)
  ElMessage.success(r.data || t('strategy.s35')); load()
}

// ── 參數編輯 ──
const edit = reactive({ show: false, id: '', keys: [], params: {}, newKey: '', newVal: 0, saving: false })
const DEFAULT_KEYS = { ma: ['fast','slow'], ema: ['fast','slow'], wma: ['fast','slow'], tema: ['fast','slow'], macd: ['fast','slow','signal'],
  boll: ['period','mult'], bollbreak: ['period','mult'], keltner: ['period','mult'], atr: ['period','mult'], volbreak: ['period','mult'],
  rsi: ['window','oversold','overbought'], grid: ['gridPct'], dualthrust: ['period','k'], momentum: ['period','threshold'], roc: ['period','threshold'],
  zscore: ['period','threshold'], vwap: ['period','dev'], sar: ['step','max'], supertrend: ['period','mult'] }
const RISK_KEYS = ['execMaker', 'makerWaitSec', 'riskPerTradePct', 'sizeCapPct', 'stopAtr', 'trailAtr', 'takeProfitPct', 'maxPositions', 'maxConsecLosses', 'maxLossUsdt', 'cooldownBars', 'regimeFilter', 'blockHighVol']
// ── 決策日誌 ──
const dlog = reactive({ show: false, id: '', symbol: '', acted: false, rows: [], loading: false })
function openLog(row) { dlog.id = row.id; dlog.symbol = row.symbol && row.symbol !== 'ALL' ? row.symbol : ''; dlog.show = true; loadLog() }
async function loadLog() {
  dlog.loading = true
  try { dlog.rows = (await strategyApi.decisions({ botId: dlog.id, symbol: dlog.symbol || undefined, acted: dlog.acted || undefined, limit: 500 })).data || [] }
  catch { dlog.rows = [] } finally { dlog.loading = false }
}
function toChart(row) { router.push({ path: '/chart', query: { symbol: row.symbol, t: row.ts, bot: row.botId } }) }
// ── 晉升管線 ──
const STAGE_TYPE = { PAPER: 'info', PILOT: 'warning', LIVE: 'success' }
const pipeline = ref({ auto: true })
async function loadPipeline() { try { pipeline.value = (await strategyApi.pipeline()).data || pipeline.value } catch {} }
async function togglePipeline(on) { try { pipeline.value = (await strategyApi.setPipeline(on)).data } catch { pipeline.value.auto = !on } }
async function changeStage(row, st) {
  const msg = st === 'PAPER' ? t('pipeline.confirmPaper') : st === 'LIVE' ? t('pipeline.confirmLive') : t('pipeline.confirmPilot')
  try { await ElMessageBox.confirm(msg, t('pipeline.st.' + st), { type: st === 'PAPER' ? 'info' : 'warning' }) } catch { return }
  const r = await strategyApi.setStage(row.id, st); ElMessage.success(r.data || 'OK'); load(true)
}
// ── 盤勢 / 自動配資 ──
const regimes = ref({})
const bandit = ref({ enabled: false, bots: [] })
const regimeCount = computed(() => {
  const c = { TREND: 0, RANGE: 0, MIXED: 0, UNKNOWN: 0, HIGHVOL: 0 }
  for (const r of Object.values(regimes.value)) { c[r.regime] = (c[r.regime] || 0) + 1; if (r.highVol) c.HIGHVOL++ }
  return c
})
async function loadRegimeBandit() {
  try { regimes.value = (await strategyApi.regimes()).data || {} } catch {}
  try { bandit.value = (await strategyApi.bandit()).data || bandit.value } catch {}
}
async function toggleBandit(on) {
  try { bandit.value = (await strategyApi.setBandit(on)).data; ElMessage.success(on ? t('bandit.on') : t('bandit.off')); load(true) }
  catch (e) { bandit.value.enabled = !on; ElMessage.error(e?.message || '') }
}
async function rebalance() { bandit.value = (await strategyApi.rebalance()).data; ElMessage.success(bandit.value.last || 'OK'); load(true) }
function openEdit(row) {
  const p = { ...(row.risk?.params || {}), ...(row.params || {}) }
  for (const k of (DEFAULT_KEYS[row.type] || ['period'])) if (!(k in p)) p[k] = null
  if (!('qty' in p)) p.qty = 0.001
  edit.id = row.id; edit.params = Object.fromEntries(Object.entries(p).map(([k, v]) => [k, v == null ? undefined : (k === 'interval' || k === 'stage') ? v : +v])); if (!edit.params.interval) edit.params.interval = '1m'; edit.keys = Object.keys(edit.params); edit.orig = { ...edit.params }; edit.show = true
}
function addKey() { if (!edit.newKey) return; edit.params[edit.newKey] = +edit.newVal; edit.keys = Object.keys(edit.params); edit.newKey = '' }
const precisionFor = v => (v != null && !Number.isInteger(+v)) ? 4 : 0
const stepFor = (k, v) => ['qty','gridPct','threshold','dev','step','max','k','mult'].includes(k) || (v != null && !Number.isInteger(+v)) ? 0.001 : 1
async function saveEdit() {
  edit.saving = true
  try {
    // 只送有改的鍵：只改風控參數時後端不重建策略、不重新暖機
    const body = Object.fromEntries(Object.entries(edit.params).filter(([k, v]) => v != null && !(typeof v === 'number' && Number.isNaN(v)) && v !== edit.orig?.[k]))
    if (!Object.keys(body).length) { edit.show = false; return }
    const r = await strategyApi.paramsAny(edit.id, body)
    if (r.code && r.code !== 0) throw new Error(r.message)
    ElMessage.success(t('strategy.s36')); edit.show = false; load()
  } catch (e) { ElMessage.error(t('strategy.s37') + (e?.message || '')) }
  finally { edit.saving = false }
}
async function createBot() {
  if (!bot.id) return ElMessage.warning(t('strategy.s38'))
  const resp = await strategyApi.create({ id: bot.id, type: bot.type, symbol: bot.symbol === 'ALL' ? null : bot.symbol, params: { qty: bot.qty, interval: bot.interval } })
  ElMessage.success(resp.data || t('strategy.s39')); bot.id = ''; load()
}
async function testSlack() {
  try { await notifyApi.test(t('strategy.s40')); ElMessage.success(t('strategy.s41')) }
  catch { ElMessage.error(t('strategy.s42')) }
}
let timer
onMounted(() => { load(); loadNotional(); loadRegimeBandit(); loadPipeline(); timer = setInterval(() => { load(true); loadNotional(); loadRegimeBandit() }, 15000) })   // 背景靜默更新，不閃 loading
onUnmounted(() => clearInterval(timer))
</script>
<style scoped>.hint{color:#999;font-size:12px;margin-top:8px}</style>
