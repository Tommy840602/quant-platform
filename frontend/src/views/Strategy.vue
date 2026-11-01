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
        <el-table-column label="收益（本次上線）" width="200">
          <template #default="{ row }">
            <template v-if="row.pnl">
              <b :style="{ color: row.pnl.pnl > 0 ? '#67c23a' : row.pnl.pnl < 0 ? '#f56c6c' : 'inherit' }">{{ sign(row.pnl.pnl) }} USDT</b>
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
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button size="small" type="success" @click="start(row.id)">上線</el-button>
            <el-button size="small" type="warning" @click="stop(row.id)">下線</el-button>
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
      <div class="hint">RSI 參數 window/oversold/overbought、網格 gridPct 走預設；建立後在上表「上線」即開始自動交易。</div>
    </el-card>
  </div>
</template>

<script setup>
// 執行時長 / 收益輔助
const fmtDur = s => { s = Math.max(0, +s || 0); const d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60); return d ? `${d}天${h}時` : h ? `${h}時${m}分` : `${m}分${s % 60}秒` }
const sign = v => (v > 0 ? '+' : '') + Number(v || 0).toFixed(2)
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { strategyApi, notifyApi } from '@/api'
import { STRATEGY_TYPES } from '@/config/strategies'
import { SYMBOLS } from '@/config/symbols'

const list = ref([]); const loading = ref(false)
const bot = reactive({ id: '', type: 'rsi', symbol: 'BTCUSDT', qty: 0.001 })

async function load() {
  loading.value = true
  try { list.value = (await strategyApi.list()).data || [] }
  catch { ElMessage.error('載入失敗（後端未啟動？）') }
  finally { loading.value = false }
}
async function start(id) { await strategyApi.start(id); ElMessage.success('已上線'); load() }
async function stop(id) { await strategyApi.stop(id); ElMessage.success('已下線'); load() }
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
onMounted(() => { load(); timer = setInterval(() => load(), 15000) })   // 執行時長 / 收益每 15 秒更新
onUnmounted(() => clearInterval(timer))
</script>
<style scoped>.hint{color:#999;font-size:12px;margin-top:8px}</style>
