<template>
  <div>
    <el-card>
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>策略 / 交易機器人</span>
          <div>
            <el-button size="small" @click="testSlack">測試 Slack 通知</el-button>
            <el-button size="small" type="primary" @click="load">重新整理</el-button>
          </div>
        </div>
      </template>
      <el-table :data="list" v-loading="loading">
        <el-table-column prop="id" label="ID" width="140" />
        <el-table-column prop="name" label="名稱" />
        <el-table-column label="標的" width="100">
        <template #default="{ row }">
          <span style="display:inline-flex;align-items:center;gap:6px"><coin-icon v-if="row.symbol && row.symbol !== 'ALL'" :symbol="row.symbol" :size="16" />{{ row.symbol }}</span>
        </template>
      </el-table-column>
        <el-table-column prop="position" label="部位" width="90" />
        <el-table-column label="執行時長" width="120">
          <template #default="{ row }">
            <span v-if="row.status === 'RUNNING'" :title="row.startedAt ? '上線於 ' + new Date(row.startedAt).toLocaleString() : ''">{{ fmtDur(row.runningSec) }}</span>
            <span v-else class="hint">—</span>
          </template>
        </el-table-column>
        <el-table-column label="收益（本次上線）" width="300">
          <template #default="{ row }">
            <template v-if="row.pnl">
              <b :style="{ color: row.pnl.pnl > 0 ? '#67c23a' : row.pnl.pnl < 0 ? '#f56c6c' : 'inherit' }">{{ sign(row.pnl.pnl) }} USDT</b>
              <svg v-if="spark(row.id)" :width="spark(row.id).w" :height="spark(row.id).hh" style="vertical-align:middle;margin-left:6px">
                <line :x1="0" :x2="spark(row.id).w" :y1="spark(row.id).zero" :y2="spark(row.id).zero" stroke="#ddd" stroke-dasharray="2,2" />
                <polyline :points="spark(row.id).pts" fill="none" :stroke="spark(row.id).color" stroke-width="1.5" />
              </svg>
              <span class="hint" :title="'已實現 ' + sign(row.pnl.realized) + ' · 未實現 ' + sign(row.pnl.unrealized) + ' · 手續費 ' + row.pnl.fees + ' · 成交 ' + row.pnl.trades + ' 筆' + (row.pnl.openQty ? ' · 持倉 ' + row.pnl.openQty + ' @ ' + row.pnl.avgCost : '')">
                {{ (row.pnl.pnlPct * 100).toFixed(2) }}% · {{ row.pnl.trades }} 筆
              </span>
            </template>
            <span v-else class="hint">{{ row.status === 'RUNNING' ? '計算中…' : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="狀態" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'RUNNING' ? 'success' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300">
          <template #default="{ row }">
            <el-button size="small" type="success" :disabled="row.status === 'RUNNING'" @click="start(row.id)">上線</el-button>
            <el-dropdown split-button size="small" type="warning" :disabled="row.status !== 'RUNNING'" @click="stop(row.id, false)" @command="c => stop(row.id, c === 'flatten')" style="margin:0 6px">
              下線
              <template #dropdown><el-dropdown-menu>
                <el-dropdown-item command="flatten">下線並平倉（市價賣出持倉）</el-dropdown-item>
              </el-dropdown-menu></template>
            </el-dropdown>
            <el-button size="small" @click="openEdit(row)">參數</el-button>
            <el-button size="small" type="danger" plain :disabled="row.builtin" :title="row.builtin ? '內建策略不可刪' : ''" @click="removeBot(row)">刪除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card header="建立交易機器人" style="margin-top:16px">
      <el-form :inline="true">
        <el-form-item label="ID"><el-input v-model="bot.id" placeholder="my-bot" style="width:130px" /></el-form-item>
        <el-form-item label="類型">
          <el-select v-model="bot.type" filterable style="width:150px">
            <el-option v-for="t in STRATEGY_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="標的">
          <symbol-select v-model="bot.symbol" style="width:150px" />
        </el-form-item>
        <el-form-item label="數量"><el-input-number v-model="bot.qty" :step="0.001" :min="0.001" /></el-form-item>
        <el-form-item><el-button type="primary" @click="createBot">建立</el-button></el-form-item>
      </el-form>
      <div class="hint">建立後可在上表「參數」調整各類型參數；建立後「上線」即開始自動交易。</div>
    </el-card>

    <el-dialog v-model="edit.show" :title="'參數 · ' + edit.id" width="480px">
      <el-form label-width="120px">
        <el-form-item v-for="k in edit.keys" :key="k" :label="k">
          <el-input-number v-model="edit.params[k]" :step="stepFor(k, edit.params[k])" :precision="precisionFor(edit.params[k])" style="width:200px" />
        </el-form-item>
        <el-form-item label="新增參數">
          <el-input v-model="edit.newKey" placeholder="例如 mult" style="width:120px;margin-right:6px" />
          <el-input-number v-model="edit.newVal" style="width:140px" />
          <el-button size="small" @click="addKey" style="margin-left:6px">加入</el-button>
        </el-form-item>
      </el-form>
      <div class="hint">參數名依類型：ma/ema/wma/tema fast,slow · macd fast,slow,signal · boll/bollbreak/keltner/atr/volbreak period,mult · rsi window,oversold,overbought · grid gridPct · dualthrust period,k · momentum/roc period,threshold · zscore period,threshold · vwap period,dev · sar step,max · 其餘 period。qty 為每筆數量（每單名目 &gt; 0 時被換算覆蓋）。運行中修改會重新暖機指標。</div>
      <template #footer>
        <el-button @click="edit.show = false">取消</el-button>
        <el-button type="primary" :loading="edit.saving" @click="saveEdit">儲存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 執行時長 / 收益輔助
const fmtDur = s => { s = Math.max(0, +s || 0); const d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60); return d ? `${d}天${h}時` : h ? `${h}時${m}分` : `${m}分${s % 60}秒` }
const sign = v => (v > 0 ? '+' : '') + Number(v || 0).toFixed(2)
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { strategyApi, notifyApi } from '@/api'
import { STRATEGY_TYPES } from '@/config/strategies'
import { SYMBOLS } from '@/config/symbols'

const list = ref([]); const loading = ref(false)
const bot = reactive({ id: '', type: 'rsi', symbol: 'BTCUSDT', qty: 0.001 })

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    list.value = (await strategyApi.list()).data || []
    // 收益走勢：每次刷新把各機器人 pnl 推進記憶體序列（最多 120 點 ≈ 30 分鐘）
    for (const b of list.value) {
      if (!b.pnl) { delete pnlHist[b.id]; continue }
      const h = (pnlHist[b.id] ||= []); h.push(+b.pnl.pnl || 0); if (h.length > 120) h.shift()
    }
  }
  catch { if (!silent) ElMessage.error('載入失敗（後端未啟動？）') }
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
async function start(id) { await strategyApi.start(id); ElMessage.success('已上線'); load() }
async function stop(id, flatten) {
  if (flatten) {
    try { await ElMessageBox.confirm(`下線 ${id} 並對其持倉送市價賣單平倉？`, '下線並平倉', { type: 'warning' }) } catch { return }
  }
  const r = await strategyApi.stop(id, flatten); ElMessage.success(r.data || '已下線'); load()
}
async function removeBot(row) {
  try { await ElMessageBox.confirm(`刪除機器人 ${row.id}？${row.status === 'RUNNING' ? '（運行中，會先下線）' : ''}`, '刪除', { type: 'warning', confirmButtonText: '刪除', cancelButtonText: '取消' }) }
  catch { return }
  let flatten = false
  if (row.position > 0) {
    try {
      await ElMessageBox.confirm(`它目前持倉 ${row.position}，要一併市價賣出平倉嗎？`, '平倉', { type: 'warning', confirmButtonText: '平倉', cancelButtonText: '不平倉', distinguishCancelAndClose: true })
      flatten = true
    } catch (e) { if (e === 'close') return }   // 關閉視窗 = 放棄；「不平倉」繼續
  }
  const r = await strategyApi.remove(row.id, flatten)
  if (r.code && r.code !== 0) return ElMessage.error(r.message)
  ElMessage.success(r.data || '已刪除'); load()
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
    ElMessage.success('已更新'); edit.show = false; load()
  } catch (e) { ElMessage.error('更新失敗：' + (e?.message || '')) }
  finally { edit.saving = false }
}
async function createBot() {
  if (!bot.id) return ElMessage.warning('請填 ID')
  const resp = await strategyApi.create({ id: bot.id, type: bot.type, symbol: bot.symbol, params: { qty: bot.qty } })
  ElMessage.success(resp.data || '已建立'); bot.id = ''; load()
}
async function testSlack() {
  try { await notifyApi.test('🔔 來自量化平台的測試通知'); ElMessage.success('已送出（未設 webhook 則僅記錄 log）') }
  catch { ElMessage.error('通知服務未啟動？') }
}
let timer
onMounted(() => { load(); timer = setInterval(() => load(true), 15000) })   // 背景靜默更新，不閃 loading
onUnmounted(() => clearInterval(timer))
</script>
<style scoped>.hint{color:#999;font-size:12px;margin-top:8px}</style>
