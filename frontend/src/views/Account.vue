<template>
  <div>
    <el-card>
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>帳戶餘額</span>
          <el-button size="small" :loading="summarizing" @click="pushSummary">推送摘要到 Slack</el-button>
        </div>
      </template>
      <el-descriptions :column="4" border v-if="balance">
        <el-descriptions-item label="狀態">
          <el-tag :type="balance.real ? 'success' : 'info'" size="small">{{ balance.real ? '真實帳戶' : '示意' }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="USDT 可用">{{ fmt(balance.free) }}</el-descriptions-item>
        <el-descriptions-item label="USDT 凍結">{{ fmt(balance.locked) }}</el-descriptions-item>
        <el-descriptions-item label="總資產 (USDT)"><b>{{ fmt(balance.totalUsdt) }}</b></el-descriptions-item>
        <el-descriptions-item label="總資產 (TWD)"><b style="color:#2962ff">{{ twd(balance.totalUsdt) }}</b></el-descriptions-item>
        <el-descriptions-item label="持有幣種">{{ assets.length }} 種</el-descriptions-item>
        <el-descriptions-item label="匯率 USDT/TWD">
          {{ rate ? rate.toFixed(3) : '…' }} <el-button link size="small" @click="loadRate">更新</el-button>
        </el-descriptions-item>
        <el-descriptions-item label="估值來源">幣安正式網行情</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="10">
        <el-card header="資產配置">
          <div ref="pieEl" style="height:340px"></div>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card>
          <template #header>
            <div style="display:flex;justify-content:space-between;align-items:center">
              <span>資產明細（估值 ≥ 1 USDT）</span>
              <el-button size="small" @click="store.refresh()">重新整理</el-button>
            </div>
          </template>
          <el-table :data="assets" size="small" max-height="360">
            <el-table-column label="幣種" width="120">
              <template #default="{ row }">
                <span style="display:inline-flex;align-items:center;gap:6px"><coin-icon :symbol="row.asset" :size="18" />{{ row.asset }}</span>
              </template>
            </el-table-column>
            <el-table-column label="數量" align="right">
              <template #default="{ row }">{{ fmt(row.free + row.locked) }}</template>
            </el-table-column>
            <el-table-column label="估值(USDT)" align="right">
              <template #default="{ row }">{{ fmt(row.valueUsdt) }}</template>
            </el-table-column>
            <el-table-column label="估值(TWD)" align="right">
              <template #default="{ row }">{{ twd(row.valueUsdt) }}</template>
            </el-table-column>
            <el-table-column label="佔比" width="140">
              <template #default="{ row }">
                <el-progress :percentage="Math.min(100, row.weight || 0)" :stroke-width="12" :format="() => (row.weight || 0).toFixed(1) + '%'" />
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card header="策略部位" style="margin-top:16px">
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
  </div>
</template>

<script setup>
import { onMounted, ref, computed, watch, nextTick } from 'vue'
import { storeToRefs } from 'pinia'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { useAccountStore } from '@/store/account'
import { accountApi } from '@/api'

const store = useAccountStore()
const { balance, positions } = storeToRefs(store)
const rate = ref(Number(localStorage.getItem('qp-usdtwd')) || 32.5)
const summarizing = ref(false)
async function pushSummary() {
  summarizing.value = true
  try { await accountApi.summaryNow(); ElMessage.success('已推送摘要到 Slack') }
  catch (e) { ElMessage.error('推送失敗：' + (e.response?.data?.message || e.message)) }
  finally { summarizing.value = false }
}
const assets = computed(() => balance.value?.balances || [])
const pieEl = ref(null)
let pie = null

function fmt(v) { return v == null ? '—' : Number(v).toLocaleString(undefined, { maximumFractionDigits: 4 }) }
function twd(v) { return v == null || !rate.value ? '—' : 'NT$ ' + (Number(v) * rate.value).toLocaleString(undefined, { maximumFractionDigits: 0 }) }

function drawPie() {
  if (!pieEl.value) return
  if (!pie) pie = echarts.init(pieEl.value)
  const top = assets.value.slice(0, 12).map(a => ({ name: a.asset, value: a.valueUsdt }))
  const restVal = assets.value.slice(12).reduce((s, a) => s + a.valueUsdt, 0)
  if (restVal > 0) top.push({ name: '其他', value: Math.round(restVal * 100) / 100 })
  pie.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} USDT ({d}%)' },
    legend: { type: 'scroll', orient: 'vertical', right: 4, top: 8, textStyle: { fontSize: 11 } },
    series: [{ type: 'pie', radius: ['40%', '70%'], center: ['38%', '50%'],
      itemStyle: { borderRadius: 4, borderWidth: 1 },
      label: { show: false }, data: top }]
  })
}

async function loadRate() {
  try {
    const r = await fetch('https://open.er-api.com/v6/latest/USD').then(x => x.json())
    if (r?.rates?.TWD) { rate.value = r.rates.TWD; localStorage.setItem('qp-usdtwd', String(rate.value)) }
  } catch {}
}

watch(assets, async () => { await nextTick(); drawPie() })
onMounted(() => { store.refresh().catch(() => {}); loadRate() })
</script>
