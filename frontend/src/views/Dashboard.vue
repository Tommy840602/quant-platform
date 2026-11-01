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
        <el-card :header="t('dashboard.s1')">
          <el-table :data="strategies" size="small">
            <el-table-column prop="name" :label="t('dashboard.s2')" />
            <el-table-column prop="status" :label="t('dashboard.s3')" width="100">
              <template #default="{ row }">
                <el-tag :type="row.status==='RUNNING'?'success':'info'" size="small">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="position" :label="t('dashboard.s4')" width="90" />
            <el-table-column :label="t('dashboard.s5')" width="110">
              <template #default="{ row }">{{ row.runningSec ? fmtDur(row.runningSec) : '—' }}</template>
            </el-table-column>
            <el-table-column :label="t('dashboard.s6')" width="130">
              <template #default="{ row }">
                <b v-if="row.pnl" :style="{ color: row.pnl.pnl > 0 ? '#67c23a' : row.pnl.pnl < 0 ? '#f56c6c' : 'inherit' }">{{ (row.pnl.pnl > 0 ? '+' : '') + Number(row.pnl.pnl).toFixed(2) }}</b>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card :header="t('dashboard.s7')">
          <el-table :data="priceRows" size="small">
            <el-table-column :label="t('dashboard.s8')" width="140">
              <template #default="{ row }">
                <span style="display:inline-flex;align-items:center;gap:6px"><coin-icon :symbol="row.symbol" :size="18" />{{ row.symbol }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('dashboard.s9')">
              <template #default="{ row }"><price-change :symbol="row.symbol" /></template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card :header="t('dashboard.s10')" style="margin-top:16px">
      <el-row :gutter="16">
        <el-col :span="4" v-for="k in kpiCards" :key="k.label">
          <div class="metric"><div class="m-label">{{ k.label }}</div><div class="m-value" :style="{color:k.color}">{{ k.value }}</div></div>
        </el-col>
      </el-row>
    </el-card>

    <el-card :header="t('dashboard.s11')" style="margin-top:16px">
      <el-table :data="orders" size="small" max-height="240">
        <el-table-column prop="clientOrderId" label="ClientOrderId" width="200" />
        <el-table-column :label="t('dashboard.s8')">
        <template #default="{ row }">
          <span style="display:inline-flex;align-items:center;gap:6px"><coin-icon v-if="row.symbol && row.symbol !== 'ALL'" :symbol="row.symbol" :size="16" />{{ row.symbol }}</span>
        </template>
      </el-table-column>
        <el-table-column prop="side" :label="t('dashboard.s12')" width="70" />
        <el-table-column prop="type" :label="t('dashboard.s13')" width="90" />
        <el-table-column prop="quantity" :label="t('dashboard.s14')" width="90" />
        <el-table-column prop="status" :label="t('dashboard.s3')" width="130" />
      </el-table>
    </el-card>

    <el-card style="margin-top:16px">
      <div class="mon">
        {{ t('dashboard.s15') }}
        <el-link href="http://localhost:3000" target="_blank" type="primary">{{ t('dashboard.s16') }}</el-link>
        （admin/admin）　
        <el-link href="http://localhost:9090" target="_blank">Prometheus :9090</el-link>
        <el-button size="small" style="float:right" @click="refresh">{{ t('dashboard.s17') }}</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
const fmtDur = s => { s = Math.max(0, +s || 0); const d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60); const p = t('common.dur').split('|'); const fill = (tpl, o) => tpl.replace(/\{(\w+)\}/g, (_, k) => o[k]); return d ? fill(p[0], { d, h }) : h ? fill(p[1], { h, m }) : fill(p[2], { m, s: s % 60 }) }
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
  { label: t('dashboard.s1'), value: strategies.value.filter(s => s.status === 'RUNNING').length, color: '#1f4e79' },
  { label: t('dashboard.s18'), value: strategies.value.length, color: '#1f4e79' },
  { label: t('dashboard.s19'), value: positions.value.length, color: '#2e9d5b' },
  { label: t('dashboard.s20'), value: orders.value.length, color: '#2e74b5' }
])
const kpiCards = computed(() => {
  const k = kpi.value
  const pct = v => v == null ? '—' : (v * 100).toFixed(1) + '%'
  return [
    { label: t('dashboard.s21'), value: k.totalPnl == null ? '—' : k.totalPnl.toFixed(2), color: (k.totalPnl || 0) >= 0 ? '#2e9d5b' : '#d9534f' },
    { label: t('dashboard.s22'), value: pct(k.winRate), color: '#1f4e79' },
    { label: t('dashboard.s23'), value: k.profitFactor == null ? '—' : k.profitFactor.toFixed(2), color: '#1f4e79' },
    { label: t('dashboard.s24'), value: k.payoffRatio == null ? '—' : k.payoffRatio.toFixed(2), color: '#1f4e79' },
    { label: t('dashboard.s25'), value: k.roundTrips ?? '—', color: '#2e74b5' },
    { label: t('dashboard.s26'), value: (k.wins ?? 0) + ' / ' + (k.losses ?? 0), color: '#666' }
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
