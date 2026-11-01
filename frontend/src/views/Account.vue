<template>
  <div>
    <el-card>
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>{{ t('account.s16') }}</span>
          <el-button size="small" :loading="summarizing" @click="pushSummary">{{ t('account.s17') }}</el-button>
        </div>
      </template>
      <el-descriptions :column="4" border v-if="balance">
        <el-descriptions-item :label="t('account.s1')">
          
          <el-tag v-if="balance.real && balance.mainnet" type="danger" effect="dark" size="small">{{ t('account.s18') }}</el-tag>
          <el-tag v-else-if="balance.real" type="warning" size="small">{{ t('account.s19') }}</el-tag>
          <el-tag v-else type="info" size="small">{{ t('account.s20') }}</el-tag>
          
          <el-tag v-if="(trade.effectiveDryRun ?? trade.dryRun) === true" type="info" size="small" style="margin-left:6px">{{ t('account.s21') }}</el-tag>
          <el-tag v-else-if="(trade.effectiveDryRun ?? trade.dryRun) === false" type="danger" effect="dark" size="small" style="margin-left:6px">{{ t('account.s22') }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item :label="t('account.s2')">{{ fmt(balance.free) }}</el-descriptions-item>
        <el-descriptions-item :label="t('account.s3')">{{ fmt(balance.locked) }}</el-descriptions-item>
        <el-descriptions-item :label="t('account.s4')"><b>{{ fmt(balance.totalUsdt) }}</b></el-descriptions-item>
        <el-descriptions-item :label="t('account.s32') + locale.currency + ')'"><b style="color:#2962ff">{{ locale.fmtMoney(balance.totalUsdt) }}</b></el-descriptions-item>
        <el-descriptions-item :label="t('account.s5')">{{ assets.length }} {{ t('account.s23') }}</el-descriptions-item>
        <el-descriptions-item :label="t('account.s33') + locale.currency">
          {{ locale.rate ? locale.rate.toFixed(3) : '…' }} <el-button link size="small" @click="locale.loadRates(true)">{{ t('account.s24') }}</el-button>
        </el-descriptions-item>
        <el-descriptions-item :label="t('account.s6')">
          {{ t('account.s25') }}<span class="hint" v-if="balance.real && !balance.mainnet">{{ t('account.s26') }}</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="10">
        <el-card :header="t('account.s7')">
          <div ref="pieEl" style="height:340px"></div>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card>
          <template #header>
            <div style="display:flex;justify-content:space-between;align-items:center">
              <span>{{ t('account.s27') }}</span>
              <el-button size="small" @click="store.refresh()">{{ t('account.s28') }}</el-button>
            </div>
          </template>
          <el-table :data="assets" size="small" max-height="360">
            <el-table-column :label="t('account.s8')" width="120">
              <template #default="{ row }">
                <span style="display:inline-flex;align-items:center;gap:6px"><coin-icon :symbol="row.asset" :size="18" />{{ row.asset }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="t('account.s9')" align="right">
              <template #default="{ row }">{{ fmt(row.free + row.locked) }}</template>
            </el-table-column>
            <el-table-column :label="t('account.s10')" align="right">
              <template #default="{ row }">{{ fmt(row.valueUsdt) }}</template>
            </el-table-column>
            <el-table-column :label="t('account.s34') + locale.currency + ')'" align="right">
              <template #default="{ row }">{{ locale.fmtMoney(row.valueUsdt) }}</template>
            </el-table-column>
            <el-table-column :label="t('account.s11')" width="140">
              <template #default="{ row }">
                <el-progress :percentage="Math.min(100, row.weight || 0)" :stroke-width="12" :format="() => (row.weight || 0).toFixed(1) + '%'" />
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card :header="t('account.s12')" style="margin-top:16px">
      <el-table :data="positions" size="small">
        <el-table-column :label="t('account.s13')">
          <template #default="{ row }">
            <span style="display:inline-flex;align-items:center;gap:6px"><coin-icon v-if="row.symbol && row.symbol !== 'ALL'" :symbol="row.symbol" :size="16" />{{ row.symbol }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="quantity" :label="t('account.s9')" />
        <el-table-column prop="avgPrice" :label="t('account.s14')" />
        <el-table-column prop="unrealizedPnl" :label="t('account.s15')" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
import { onMounted, ref, computed, watch, nextTick } from 'vue'
import { storeToRefs } from 'pinia'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { useAccountStore } from '@/store/account'
import { accountApi } from '@/api'
import { useTradeStore } from '@/store/trade'
import { useLocaleStore } from '@/store/locale'
const locale = useLocaleStore()

const store = useAccountStore()
const { balance, positions } = storeToRefs(store)
const summarizing = ref(false)
const tradeStore = useTradeStore()
const { cfg: trade } = storeToRefs(tradeStore)
async function pushSummary() {
  summarizing.value = true
  try { await accountApi.summaryNow(); ElMessage.success(t('account.s29')) }
  catch (e) { ElMessage.error(t('account.s30') + (e.response?.data?.message || e.message)) }
  finally { summarizing.value = false }
}
const assets = computed(() => balance.value?.balances || [])
const pieEl = ref(null)
let pie = null

function fmt(v) { return v == null ? '—' : Number(v).toLocaleString(undefined, { maximumFractionDigits: 4 }) }

function drawPie() {
  if (!pieEl.value) return
  if (!pie) pie = echarts.init(pieEl.value)
  const top = assets.value.slice(0, 12).map(a => ({ name: a.asset, value: a.valueUsdt }))
  const restVal = assets.value.slice(12).reduce((s, a) => s + a.valueUsdt, 0)
  if (restVal > 0) top.push({ name: t('account.s31'), value: Math.round(restVal * 100) / 100 })
  pie.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} USDT ({d}%)' },
    legend: { type: 'scroll', orient: 'vertical', right: 4, top: 8, textStyle: { fontSize: 11 } },
    series: [{ type: 'pie', radius: ['40%', '70%'], center: ['38%', '50%'],
      itemStyle: { borderRadius: 4, borderWidth: 1 },
      label: { show: false }, data: top }]
  })
}


watch(assets, async () => { await nextTick(); drawPie() })
onMounted(() => { store.refresh().catch(() => {}); locale.loadRates(); tradeStore.load() })
</script>

<style scoped>
.hint { color:#999; font-size:12px; margin-left:6px; }
</style>
