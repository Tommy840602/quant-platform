<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="10">
        <el-card :header="t('live.s1')">
          <el-form label-width="80px">
            <el-form-item :label="t('live.s2')">
              <el-radio-group v-model="ticket.market">
                <el-radio-button label="SPOT">{{ t('live.s25') }}</el-radio-button>
                <el-radio-button label="USDT_M">{{ t('live.s26') }}</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item :label="t('live.s3')">
              <symbol-select v-model="ticket.symbol" style="width:100%" />
            </el-form-item>
            <el-form-item :label="t('live.s4')">
              <el-radio-group v-model="ticket.side">
                <el-radio-button label="BUY">{{ t('live.s27') }}</el-radio-button>
                <el-radio-button label="SELL">{{ t('live.s28') }}</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item :label="t('live.s5')">
              <el-select v-model="ticket.type" style="width:100%">
                <el-option label="MARKET" value="MARKET" />
                <el-option label="LIMIT" value="LIMIT" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="ticket.type==='LIMIT'" :label="t('live.s6')">
              <el-input-number v-model="ticket.price" :step="priceStep" :min="0" :precision="pricePrecision" style="width:180px" />
              <el-button size="small" style="margin-left:8px" @click="ticket.price = prices[ticket.symbol] || 0">{{ t('live.s29') }}</el-button>
              <span class="hint" v-if="prices[ticket.symbol]">{{ t('live.s30') }} {{ prices[ticket.symbol] }}
                <template v-if="ticket.price > 0">｜ {{ ((ticket.price / prices[ticket.symbol] - 1) * 100).toFixed(2) }}%</template></span>
            </el-form-item>
            <el-form-item :label="t('live.s7')">
              <el-input-number v-model="ticket.quantity" :step="0.001" :min="0.001" />
            </el-form-item>
            <el-form-item v-if="ticket.market==='USDT_M'" :label="t('live.s8')">
              <el-input-number v-model="ticket.leverage" :min="1" :max="125" />
              <el-button size="small" style="margin-left:8px" @click="setLev">{{ t('live.s31') }}</el-button>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="submitting" @click="submit">{{ t('live.s32') }}</el-button>
              <el-button :disabled="!lastId" @click="query">{{ t('live.s33') }}</el-button>
              <el-button type="danger" :disabled="!lastId" @click="cancel">{{ t('live.s34') }}</el-button>
            </el-form-item>
          </el-form>
          <div v-if="lastId" class="last">{{ t('live.s35') }}{{ lastId }}（{{ lastSymbol }} · {{ lastMarket }}）</div>
          <el-alert v-if="lastResp" :title="lastResp" type="info" :closable="false" style="margin-top:8px" />
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card>
          <template #header>
            <div style="display:flex;justify-content:space-between;align-items:center">
              <span>{{ t('live.s36') }}</span>
              <el-button size="small" @click="loadPositions">{{ t('live.s37') }}</el-button>
            </div>
          </template>
          <el-table :data="positions" size="small">
            <el-table-column :label="t('live.s3')">
        <template #default="{ row }">
          <span style="display:inline-flex;align-items:center;gap:6px"><coin-icon v-if="row.symbol && row.symbol !== 'ALL'" :symbol="row.symbol" :size="16" />{{ row.symbol }}</span>
        </template>
      </el-table-column>
            <el-table-column prop="quantity" :label="t('live.s7')" />
            <el-table-column prop="avgPrice" :label="t('live.s9')" />
            <el-table-column prop="unrealizedPnl" :label="t('live.s10')" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
    <el-card :header="t('live.s11')" style="margin-top:16px">
      <el-form :inline="true">
        <el-form-item :label="t('live.s3')">
          <symbol-select v-model="condForm.symbol" style="width:130px" />
        </el-form-item>
        <el-form-item :label="t('live.s12')">
          <el-select v-model="condForm.type" style="width:150px">
            <el-option :label="t('live.s13')" value="STOP_LOSS" />
            <el-option :label="t('live.s14')" value="TAKE_PROFIT" />
            <el-option :label="t('live.s15')" value="TRAILING_STOP" />
            <el-option :label="t('live.s16')" value="PRICE_ABOVE" />
            <el-option :label="t('live.s17')" value="PRICE_BELOW" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('live.s4')">
          <el-select v-model="condForm.side" style="width:90px">
            <el-option :label="t('live.s18')" value="SELL" /><el-option :label="t('live.s19')" value="BUY" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('live.s7')"><el-input-number v-model="condForm.quantity" :step="0.001" :min="0.001" /></el-form-item>
        <el-form-item v-if="condForm.type!=='TRAILING_STOP'" :label="t('live.s20')"><el-input-number v-model="condForm.triggerPrice" :step="1" /></el-form-item>
        <el-form-item v-else :label="t('live.s21')"><el-input-number v-model="condForm.trailPctUi" :step="0.5" :min="0.1" /></el-form-item>
        <el-form-item><el-button type="primary" @click="createCond">{{ t('live.s38') }}</el-button></el-form-item>
      </el-form>
      <el-table :data="conds" size="small" style="margin-top:8px">
        <el-table-column prop="id" label="#" width="60" />
        <el-table-column :label="t('live.s3')" width="100">
        <template #default="{ row }">
          <span style="display:inline-flex;align-items:center;gap:6px"><coin-icon v-if="row.symbol && row.symbol !== 'ALL'" :symbol="row.symbol" :size="16" />{{ row.symbol }}</span>
        </template>
      </el-table-column>
        <el-table-column prop="type" :label="t('live.s12')" width="140" />
        <el-table-column prop="side" :label="t('live.s4')" width="70" />
        <el-table-column prop="quantity" :label="t('live.s7')" width="90" />
        <el-table-column prop="triggerPrice" :label="t('live.s20')" width="100" />
        <el-table-column prop="trailPct" :label="t('live.s22')" width="80" />
        <el-table-column prop="status" :label="t('live.s23')" width="110" />
        <el-table-column :label="t('live.s24')" width="90">
          <template #default="{ row }">
            <el-button size="small" type="danger" :disabled="row.status!=='PENDING'" @click="cancelCond(row.id)">{{ t('live.s39') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
import { reactive, ref, computed, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { orderApi, accountApi } from '@/api'
import { useMarketStore } from '@/store/market'
import { SYMBOLS } from '@/config/symbols'
import http from '@/api/http'

const market = useMarketStore()
const { connected, prices } = storeToRefs(market)

const ticket = reactive({ market: 'SPOT', account: 'main', symbol: 'BTCUSDT', side: 'BUY', type: 'MARKET', quantity: 0.001, leverage: 5, price: 0 })
// 限價輸入的步進 / 小數位依現價量級（BTC 0.1、小幣 0.0001）
const pricePrecision = computed(() => { const p = prices.value[ticket.symbol] || 0; return p >= 1000 ? 1 : p >= 10 ? 2 : p >= 1 ? 3 : 5 })
const priceStep = computed(() => Math.pow(10, -pricePrecision.value))
// 切成 LIMIT 或換標的時預設帶現價，之後由使用者改
watch(() => [ticket.type, ticket.symbol], () => { if (ticket.type === 'LIMIT' && !(ticket.price > 0)) ticket.price = prices.value[ticket.symbol] || 0 })
const submitting = ref(false)
const lastResp = ref(''); const lastId = ref(''); const lastSymbol = ref(''); const lastMarket = ref('SPOT')
const positions = ref([])

async function submit() {
  if (ticket.type === 'LIMIT' && !(ticket.price > 0)) { ElMessage.warning(t('live.s40')); return }
  submitting.value = true
  try {
    const id = 'ui-' + Date.now()
    const intent = {
      clientOrderId: id, strategyId: 'manual', symbol: ticket.symbol,
      side: ticket.side, type: ticket.type,
      price: ticket.type === 'LIMIT' ? ticket.price : null,
      quantity: ticket.quantity, reduceOnly: false, market: ticket.market, account: ticket.account
    }
    const resp = await orderApi.submit(intent)
    lastId.value = id; lastSymbol.value = ticket.symbol; lastMarket.value = ticket.market
    lastResp.value = t('live.s55', { code: resp.code, msg: resp.message })
    ElMessage.success(t('live.s41'))
    setTimeout(loadPositions, 800)
  } catch (e) {
    lastResp.value = t('live.s42') + (e.response?.data?.message || e.message)
    ElMessage.error(t('live.s43'))
  } finally { submitting.value = false }
}

async function query() {
  try {
    const resp = await orderApi.query(lastId.value, lastSymbol.value, lastMarket.value)
    lastResp.value = t('live.s44') + (resp.data || resp.message)
  } catch (e) { lastResp.value = t('live.s45') + (e.response?.data?.message || e.message) }
}
async function cancel() {
  try {
    const resp = await orderApi.cancel(lastId.value, lastSymbol.value, lastMarket.value)
    lastResp.value = t('live.s46') + (resp.data || resp.message)
    ElMessage.success(t('live.s47')); setTimeout(loadPositions, 800)
  } catch (e) { lastResp.value = t('live.s48') + (e.response?.data?.message || e.message) }
}
async function setLev() {
  try {
    const resp = await http.post('/order/orders/leverage', null, { params: { symbol: ticket.symbol, leverage: ticket.leverage } })
    lastResp.value = t('live.s49') + (resp.data || resp.message)
    ElMessage.success(t('live.s50'))
  } catch (e) { lastResp.value = t('live.s51') + (e.response?.data?.message || e.message) }
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
  try { await orderApi.condCreate(body); ElMessage.success(t('live.s52')); loadConds() }
  catch (e) { ElMessage.error(t('live.s53') + (e.response?.data?.message || e.message)) }
}
async function cancelCond(id) { await orderApi.condCancel(id); ElMessage.success(t('live.s54')); loadConds() }
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
