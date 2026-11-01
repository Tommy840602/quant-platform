<template>
  <div>
    <el-card>
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>{{ t('strategy.s13') }}</span>
          <div>
            <el-button size="small" @click="testSlack">{{ t('strategy.s14') }}</el-button>
            <el-button size="small" type="primary" @click="load">{{ t('strategy.s15') }}</el-button>
          </div>
        </div>
      </template>
      <el-table :data="list" v-loading="loading">
        <el-table-column prop="id" label="ID" width="140" />
        <el-table-column prop="name" :label="t('strategy.s1')" />
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
        <el-table-column prop="status" :label="t('strategy.s6')" width="210">
          <template #default="{ row }">
            <el-tag :type="row.status === 'RUNNING' ? 'success' : 'info'">{{ row.status }}</el-tag>
            <div v-if="row.status === 'RUNNING' && row.diag" class="hint" style="line-height:1.5;margin-top:4px">
              <div :title="row.diag.lastBarAt ? t('strategy.diagLastBar') + locale.fmtTime(row.diag.lastBarAt) : ''">
                {{ t('strategy.diagBars', { n: row.diag.closedBars, syms: row.diag.symbols }) }}
                <span v-if="!row.diag.closedBars" style="color:#e6a23c">· {{ t('strategy.diagNoBars') }}</span>
                <span v-else-if="row.diag.closedBars < (row.params?.slow || 8) * row.diag.symbols" style="color:#e6a23c">· {{ t('strategy.diagWarmup', { n: row.params?.slow || 8 }) }}</span>
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
            <el-button size="small" type="danger" plain :disabled="row.builtin" :title="row.builtin ? t('strategy.s51') : ''" @click="removeBot(row)">{{ t('strategy.s21') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
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
        <el-form-item :label="t('strategy.s10')"><el-input-number v-model="bot.qty" :step="0.001" :min="0.001" /></el-form-item>
        <el-form-item><el-button type="primary" @click="createBot">{{ t('strategy.s22') }}</el-button></el-form-item>
      </el-form>
      <div class="hint">{{ t('strategy.s23') }}</div>
    </el-card>

    <el-dialog v-model="edit.show" :title="t('strategy.s52') + edit.id" width="480px">
      <el-form label-width="120px">
        <el-form-item v-for="k in edit.keys" :key="k" :label="k">
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
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { strategyApi, notifyApi } from '@/api'
import { STRATEGY_TYPES } from '@/config/strategies'
import { useLocaleStore } from '@/store/locale'
const locale = useLocaleStore()
import { SYMBOLS } from '@/config/symbols'

const list = ref([]); const loading = ref(false)
const bot = reactive({ id: '', type: 'rsi', symbol: 'BTCUSDT', qty: 0.001 })

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
async function start(id) { await strategyApi.start(id); ElMessage.success(t('strategy.s29')); load() }
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
function openEdit(row) {
  const p = { ...(row.params || {}) }
  for (const k of (DEFAULT_KEYS[row.type] || ['period'])) if (!(k in p)) p[k] = null
  if (!('qty' in p)) p.qty = 0.001
  edit.id = row.id; edit.params = Object.fromEntries(Object.entries(p).map(([k, v]) => [k, v == null ? undefined : +v])); edit.keys = Object.keys(edit.params); edit.show = true
}
function addKey() { if (!edit.newKey) return; edit.params[edit.newKey] = +edit.newVal; edit.keys = Object.keys(edit.params); edit.newKey = '' }
const precisionFor = v => (v != null && !Number.isInteger(+v)) ? 4 : 0
const stepFor = (k, v) => ['qty','gridPct','threshold','dev','step','max','k','mult'].includes(k) || (v != null && !Number.isInteger(+v)) ? 0.001 : 1
async function saveEdit() {
  edit.saving = true
  try {
    const body = Object.fromEntries(Object.entries(edit.params).filter(([, v]) => v != null && v !== undefined && !Number.isNaN(v)))
    const r = await strategyApi.paramsAny(edit.id, body)
    if (r.code && r.code !== 0) throw new Error(r.message)
    ElMessage.success(t('strategy.s36')); edit.show = false; load()
  } catch (e) { ElMessage.error(t('strategy.s37') + (e?.message || '')) }
  finally { edit.saving = false }
}
async function createBot() {
  if (!bot.id) return ElMessage.warning(t('strategy.s38'))
  const resp = await strategyApi.create({ id: bot.id, type: bot.type, symbol: bot.symbol === 'ALL' ? null : bot.symbol, params: { qty: bot.qty } })
  ElMessage.success(resp.data || t('strategy.s39')); bot.id = ''; load()
}
async function testSlack() {
  try { await notifyApi.test(t('strategy.s40')); ElMessage.success(t('strategy.s41')) }
  catch { ElMessage.error(t('strategy.s42')) }
}
let timer
onMounted(() => { load(); timer = setInterval(() => load(true), 15000) })   // 背景靜默更新，不閃 loading
onUnmounted(() => clearInterval(timer))
</script>
<style scoped>.hint{color:#999;font-size:12px;margin-top:8px}</style>
