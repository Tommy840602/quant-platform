<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="10">
        <el-card header="手動下單 / 查單 / 撤單">
          <el-form label-width="80px">
            <el-form-item label="市場">
              <el-radio-group v-model="ticket.market">
                <el-radio-button label="SPOT">現貨</el-radio-button>
                <el-radio-button label="USDT_M">合約(U本位)</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="標的">
              <symbol-select v-model="ticket.symbol" style="width:100%" />
            </el-form-item>
            <el-form-item label="方向">
              <el-radio-group v-model="ticket.side">
                <el-radio-button label="BUY">買入/做多</el-radio-button>
                <el-radio-button label="SELL">賣出/做空</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="類型">
              <el-select v-model="ticket.type" style="width:100%">
                <el-option label="MARKET" value="MARKET" />
                <el-option label="LIMIT" value="LIMIT" />
              </el-select>
            </el-form-item>
            <el-form-item label="數量">
              <el-input-number v-model="ticket.quantity" :step="0.001" :min="0.001" />
            </el-form-item>
            <el-form-item v-if="ticket.market==='USDT_M'" label="槓桿">
              <el-input-number v-model="ticket.leverage" :min="1" :max="125" />
              <el-button size="small" style="margin-left:8px" @click="setLev">設定</el-button>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="submitting" @click="submit">送出下單</el-button>
              <el-button :disabled="!lastId" @click="query">查單</el-button>
              <el-button type="danger" :disabled="!lastId" @click="cancel">撤單</el-button>
            </el-form-item>
          </el-form>
          <div v-if="lastId" class="last">最近：{{ lastId }}（{{ lastSymbol }} · {{ lastMarket }}）</div>
          <el-alert v-if="lastResp" :title="lastResp" type="info" :closable="false" style="margin-top:8px" />
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card>
          <template #header>
            <div style="display:flex;justify-content:space-between;align-items:center">
              <span>部位（account 由成交事件更新，已落地 DB）</span>
              <el-button size="small" @click="loadPositions">重新整理</el-button>
            </div>
          </template>
          <el-table :data="positions" size="small">
            <el-table-column label="標的">
        <template #default="{ row }">
          <span style="display:inline-flex;align-items:center;gap:6px"><coin-icon v-if="row.symbol && row.symbol !== 'ALL'" :symbol="row.symbol" :size="16" />{{ row.symbol }}</span>
        </template>
      </el-table-column>
            <el-table-column prop="quantity" label="數量" />
            <el-table-column prop="avgPrice" label="均價" />
            <el-table-column prop="unrealizedPnl" label="未實現盈虧" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
    <el-card header="智慧單 / 條件單（自動停損 · 停利 · 移動鎖利）" style="margin-top:16px">
      <el-form :inline="true">
        <el-form-item label="標的">
          <symbol-select v-model="condForm.symbol" style="width:130px" />
        </el-form-item>
        <el-form-item label="條件">
          <el-select v-model="condForm.type" style="width:150px">
            <el-option label="停損(跌破賣)" value="STOP_LOSS" />
            <el-option label="停利(漲到賣)" value="TAKE_PROFIT" />
            <el-option label="移動鎖利" value="TRAILING_STOP" />
            <el-option label="突破價買/賣" value="PRICE_ABOVE" />
            <el-option label="跌破價買/賣" value="PRICE_BELOW" />
          </el-select>
        </el-form-item>
        <el-form-item label="方向">
          <el-select v-model="condForm.side" style="width:90px">
            <el-option label="賣" value="SELL" /><el-option label="買" value="BUY" />
          </el-select>
        </el-form-item>
        <el-form-item label="數量"><el-input-number v-model="condForm.quantity" :step="0.001" :min="0.001" /></el-form-item>
        <el-form-item v-if="condForm.type!=='TRAILING_STOP'" label="觸發價"><el-input-number v-model="condForm.triggerPrice" :step="1" /></el-form-item>
        <el-form-item v-else label="回撤%"><el-input-number v-model="condForm.trailPctUi" :step="0.5" :min="0.1" /></el-form-item>
        <el-form-item><el-button type="primary" @click="createCond">建立</el-button></el-form-item>
      </el-form>
      <el-table :data="conds" size="small" style="margin-top:8px">
        <el-table-column prop="id" label="#" width="60" />
        <el-table-column label="標的" width="100">
        <template #default="{ row }">
          <span style="display:inline-flex;align-items:center;gap:6px"><coin-icon v-if="row.symbol && row.symbol !== 'ALL'" :symbol="row.symbol" :size="16" />{{ row.symbol }}</span>
        </template>
      </el-table-column>
        <el-table-column prop="type" label="條件" width="140" />
        <el-table-column prop="side" label="方向" width="70" />
        <el-table-column prop="quantity" label="數量" width="90" />
        <el-table-column prop="triggerPrice" label="觸發價" width="100" />
        <el-table-column prop="trailPct" label="回撤" width="80" />
        <el-table-column prop="status" label="狀態" width="110" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" type="danger" :disabled="row.status!=='PENDING'" @click="cancelCond(row.id)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { orderApi, accountApi } from '@/api'
import { useMarketStore } from '@/store/market'
import { SYMBOLS } from '@/config/symbols'
import http from '@/api/http'

const market = useMarketStore()
const { connected, prices } = storeToRefs(market)

const ticket = reactive({ market: 'SPOT', account: 'main', symbol: 'BTCUSDT', side: 'BUY', type: 'MARKET', quantity: 0.001, leverage: 5 })
const submitting = ref(false)
const lastResp = ref(''); const lastId = ref(''); const lastSymbol = ref(''); const lastMarket = ref('SPOT')
const positions = ref([])

async function submit() {
  submitting.value = true
  try {
    const id = 'ui-' + Date.now()
    const intent = {
      clientOrderId: id, strategyId: 'manual', symbol: ticket.symbol,
      side: ticket.side, type: ticket.type,
      price: ticket.type === 'LIMIT' ? (prices.value[ticket.symbol] || 0) : null,
      quantity: ticket.quantity, reduceOnly: false, market: ticket.market, account: ticket.account
    }
    const resp = await orderApi.submit(intent)
    lastId.value = id; lastSymbol.value = ticket.symbol; lastMarket.value = ticket.market
    lastResp.value = `下單 code=${resp.code} · ${resp.message}`
    ElMessage.success('已送出')
    setTimeout(loadPositions, 800)
  } catch (e) {
    lastResp.value = '下單失敗：' + (e.response?.data?.message || e.message)
    ElMessage.error('下單失敗')
  } finally { submitting.value = false }
}

async function query() {
  try {
    const resp = await orderApi.query(lastId.value, lastSymbol.value, lastMarket.value)
    lastResp.value = '查單：' + (resp.data || resp.message)
  } catch (e) { lastResp.value = '查單失敗：' + (e.response?.data?.message || e.message) }
}
async function cancel() {
  try {
    const resp = await orderApi.cancel(lastId.value, lastSymbol.value, lastMarket.value)
    lastResp.value = '撤單：' + (resp.data || resp.message)
    ElMessage.success('已送撤單'); setTimeout(loadPositions, 800)
  } catch (e) { lastResp.value = '撤單失敗：' + (e.response?.data?.message || e.message) }
}
async function setLev() {
  try {
    const resp = await http.post('/order/orders/leverage', null, { params: { symbol: ticket.symbol, leverage: ticket.leverage } })
    lastResp.value = '設定槓桿：' + (resp.data || resp.message)
    ElMessage.success('槓桿已送出')
  } catch (e) { lastResp.value = '槓桿設定失敗：' + (e.response?.data?.message || e.message) }
}
async function loadPositions() {
  try { positions.value = (await accountApi.positions()).data || [] } catch { positions.value = [] }
}
loadPositions()

const condForm = reactive({ symbol: 'BTCUSDT', type: 'STOP_LOSS', side: 'SELL', quantity: 0.001, triggerPrice: 60000, trailPctUi: 3 })
const conds = ref([])
async function loadConds() { try { conds.value = (await orderApi.condList()).data || [] } catch { conds.value = [] } }
async function createCond() {
  const body = { symbol: condForm.symbol, type: condForm.type, side: condForm.side, quantity: condForm.quantity, market: 'SPOT', account: 'main' }
  if (condForm.type === 'TRAILING_STOP') body.trailPct = condForm.trailPctUi / 100
  else body.triggerPrice = condForm.triggerPrice
  try { await orderApi.condCreate(body); ElMessage.success('條件單已建立'); loadConds() }
  catch (e) { ElMessage.error('建立失敗：' + (e.response?.data?.message || e.message)) }
}
async function cancelCond(id) { await orderApi.condCancel(id); ElMessage.success('已取消'); loadConds() }
loadConds()

// SSE 即時推播（見架構文件 §11.3）：後端每 3 秒推部位/回撤，取代輪詢
import { onMounted, onUnmounted } from 'vue'
import { getToken } from '@/api/http'
let es = null
onMounted(() => {
  try {
    const tk = getToken()
    es = new EventSource('/api/account/stream' + (tk ? '?access_token=' + encodeURIComponent(tk) : ''))
    es.addEventListener('snapshot', (ev) => {
      try {
        const d = JSON.parse(ev.data)
        if (d.positions) positions.value = d.positions
      } catch {}
    })
  } catch {}
})
onUnmounted(() => { if (es) es.close() })
</script>
<style scoped>.last{color:#888;font-size:12px;margin-top:6px}</style>
