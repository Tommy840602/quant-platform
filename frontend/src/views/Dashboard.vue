<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="6" v-for="c in cards" :key="c.label">
        <el-card shadow="hover">
          <div class="stat-label">{{ c.label }}</div>
          <div class="stat-value" :style="{ color: c.color }">{{ c.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="12">
        <el-card header="運行中策略">
          <el-table :data="strategies" size="small">
            <el-table-column prop="name" label="策略" />
            <el-table-column prop="status" label="狀態" width="100">
              <template #default="{ row }">
                <el-tag :type="row.status==='RUNNING'?'success':'info'" size="small">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="position" label="部位" width="90" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card header="即時價格">
          <el-table :data="priceRows" size="small">
            <el-table-column label="標的" width="140">
              <template #default="{ row }">
                <span style="display:inline-flex;align-items:center;gap:6px"><coin-icon :symbol="row.symbol" :size="18" />{{ row.symbol }}</span>
              </template>
            </el-table-column>
            <el-table-column label="最新價 / 24h">
              <template #default="{ row }"><price-change :symbol="row.symbol" /></template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card header="盈虧 / 勝率 KPI" style="margin-top:16px">
      <el-row :gutter="16">
        <el-col :span="4" v-for="k in kpiCards" :key="k.label">
          <div class="metric"><div class="m-label">{{ k.label }}</div><div class="m-value" :style="{color:k.color}">{{ k.value }}</div></div>
        </el-col>
      </el-row>
    </el-card>

    <el-card header="近期訂單（DB）" style="margin-top:16px">
      <el-table :data="orders" size="small" max-height="240">
        <el-table-column prop="clientOrderId" label="ClientOrderId" width="200" />
        <el-table-column label="標的">
        <template #default="{ row }">
          <span style="display:inline-flex;align-items:center;gap:6px"><coin-icon v-if="row.symbol && row.symbol !== 'ALL'" :symbol="row.symbol" :size="16" />{{ row.symbol }}</span>
        </template>
      </el-table-column>
        <el-table-column prop="side" label="方向" width="70" />
        <el-table-column prop="type" label="類型" width="90" />
        <el-table-column prop="quantity" label="數量" width="90" />
        <el-table-column prop="status" label="狀態" width="130" />
      </el-table>
    </el-card>

    <el-card style="margin-top:16px">
      <div class="mon">
        系統監控（Prometheus + Grafana）：
        <el-link href="http://localhost:3000" target="_blank" type="primary">Grafana 儀表板 :3000</el-link>
        （admin/admin）　
        <el-link href="http://localhost:9090" target="_blank">Prometheus :9090</el-link>
        <el-button size="small" style="float:right" @click="refresh">重新整理</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useMarketStore } from '@/store/market'
import { strategyApi, accountApi, orderApi } from '@/api'

const market = useMarketStore()
const { prices } = storeToRefs(market)
const strategies = ref([])
const orders = ref([])
const positions = ref([])
const kpi = ref({})

const cards = computed(() => [
  { label: '運行中策略', value: strategies.value.filter(s => s.status === 'RUNNING').length, color: '#1f4e79' },
  { label: '策略總數', value: strategies.value.length, color: '#1f4e79' },
  { label: '持倉標的', value: positions.value.length, color: '#2e9d5b' },
  { label: '近期訂單', value: orders.value.length, color: '#2e74b5' }
])
const kpiCards = computed(() => {
  const k = kpi.value
  const pct = v => v == null ? '—' : (v * 100).toFixed(1) + '%'
  return [
    { label: '總損益', value: k.totalPnl == null ? '—' : k.totalPnl.toFixed(2), color: (k.totalPnl || 0) >= 0 ? '#2e9d5b' : '#d9534f' },
    { label: '勝率', value: pct(k.winRate), color: '#1f4e79' },
    { label: '獲利因子', value: k.profitFactor == null ? '—' : k.profitFactor.toFixed(2), color: '#1f4e79' },
    { label: '盈虧比', value: k.payoffRatio == null ? '—' : k.payoffRatio.toFixed(2), color: '#1f4e79' },
    { label: '完成回合', value: k.roundTrips ?? '—', color: '#2e74b5' },
    { label: '勝/負', value: (k.wins ?? 0) + ' / ' + (k.losses ?? 0), color: '#666' }
  ]
})
const priceRows = computed(() =>
  Object.entries(prices.value).map(([symbol, price]) => ({ symbol, price })))

async function refresh() {
  try { strategies.value = (await strategyApi.list()).data || [] } catch {}
  try { positions.value = (await accountApi.positions()).data || [] } catch {}
  try { orders.value = (await orderApi.recent(50)).data || [] } catch {}
  try { kpi.value = (await orderApi.kpi()).data || {} } catch {}
}
onMounted(refresh)
</script>

<style scoped>
.stat-label { color:#888; font-size:13px; }
.stat-value { font-size:26px; font-weight:700; margin-top:6px; }
.mon { font-size:13px; color:#555; }
</style>
