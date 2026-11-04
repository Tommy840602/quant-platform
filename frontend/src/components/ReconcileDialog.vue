<template>
  <el-dialog v-model="show" :title="t('recon.title') + ' · ' + botId" width="860px" destroy-on-close>
    <div style="display:flex;align-items:center;gap:8px;margin-bottom:12px;flex-wrap:wrap">
      <el-radio-group v-model="days" size="small" @change="load">
        <el-radio-button :value="7">7d</el-radio-button>
        <el-radio-button :value="30">30d</el-radio-button>
        <el-radio-button :value="90">90d</el-radio-button>
      </el-radio-group>
      <span class="hint">{{ t('recon.intro') }}</span>
    </div>
    <div v-loading="loading">
      <template v-if="d">
        <h4 class="sec">{{ t('recon.modes') }}</h4>
        <el-table :data="modeRows" size="small" border>
          <el-table-column :label="t('recon.mode')" width="130">
            <template #default="{ row }"><el-tag size="small" :type="MODE_TYPE[row.mode]">{{ t('recon.m.' + row.mode) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="trips" :label="t('recon.trips')" align="right" />
          <el-table-column :label="t('recon.winRate')" align="right"><template #default="{ row }">{{ row.winRate == null ? '—' : row.winRate + '%' }}</template></el-table-column>
          <el-table-column :label="t('recon.pnl')" align="right"><template #default="{ row }"><span :style="pc(row.pnl)">{{ row.pnl }}</span></template></el-table-column>
          <el-table-column :label="t('recon.avgPnl')" align="right"><template #default="{ row }">{{ row.avgPnl ?? '—' }}</template></el-table-column>
          <el-table-column :label="t('recon.meanRet')" align="right"><template #default="{ row }">{{ row.meanRetPct == null ? '—' : row.meanRetPct + '%' }}</template></el-table-column>
        </el-table>

        <h4 class="sec">{{ t('recon.exec') }}</h4>
        <div class="bars">
          <div class="kv"><span>{{ t('recon.m.SHADOW') }}</span><b :style="pc(ex.shadowPnl)">{{ ex.shadowPnl }}</b></div>
          <div class="kv"><span>{{ t('recon.fees') }}</span><b :style="pc(ex.fees)">{{ ex.fees }}</b></div>
          <div class="kv"><span>{{ t('recon.slip') }}<span class="hint" v-if="ex.slipBps != null">（{{ ex.slipBps }} bps · {{ ex.slipFillsWithQty }}/{{ ex.slipFills }}）</span></span><b :style="pc(ex.slippage)">{{ ex.slippage }}</b></div>
          <div class="kv"><span>{{ t('recon.other') }}<span class="hint">（{{ t('recon.otherHint', { missed: ex.missedTrips, rej: ex.rejectsSinceStart }) }}）</span></span><b :style="pc(ex.other)">{{ ex.other }}</b></div>
          <div class="kv total"><span>= {{ t('recon.m.LIVE') }}</span><b :style="pc(ex.livePnl)">{{ ex.livePnl }}</b></div>
          <div class="hint" v-if="ex.costPerTrip != null">{{ t('recon.perTrip', { c: ex.costPerTrip }) }}</div>
          <div class="hint" v-if="ex.slipFills && ex.slipFillsWithQty < ex.slipFills">{{ t('recon.oldSlip') }}</div>
        </div>

        <h4 class="sec">{{ t('recon.promo') }}</h4>
        <div v-if="pr.enough" class="kv-row">
          <span>{{ t('recon.m.PAPER') }} {{ pr.paperMeanRetPct }}% / {{ t('recon.winRate') }} {{ pr.paperWinRate }}%</span>
          <span>→</span>
          <span>{{ t('recon.m.LIVE') }} {{ pr.liveMeanRetPct }}% / {{ t('recon.winRate') }} {{ pr.liveWinRate }}%</span>
          <el-tag :type="pr.deltaPct >= 0 ? 'success' : (pr.deltaPct > -0.1 ? 'warning' : 'danger')" size="small">{{ t('recon.delta') }} {{ pr.deltaPct > 0 ? '+' : '' }}{{ pr.deltaPct }}% / {{ t('recon.perTripShort') }}</el-tag>
        </div>
        <div v-else class="hint">{{ t('recon.notEnough') }}</div>

        <h4 class="sec">{{ t('recon.bySymbol') }}</h4>
        <el-table :data="d.symbols" size="small" max-height="300" :empty-text="t('recon.empty')">
          <el-table-column prop="symbol" :label="t('recon.symbol')" min-width="110" />
          <el-table-column :label="t('recon.m.PAPER')" align="right"><template #default="{ row }">{{ row.paperN }} · <span :style="pc(row.paperPnl)">{{ row.paperPnl }}</span></template></el-table-column>
          <el-table-column :label="t('recon.m.SHADOW')" align="right"><template #default="{ row }">{{ row.shadowN }} · <span :style="pc(row.shadowPnl)">{{ row.shadowPnl }}</span></template></el-table-column>
          <el-table-column :label="t('recon.m.LIVE')" align="right"><template #default="{ row }">{{ row.liveN }} · <span :style="pc(row.livePnl)">{{ row.livePnl }}</span></template></el-table-column>
          <el-table-column :label="t('recon.gap')" align="right"><template #default="{ row }"><b :style="pc(row.gap)">{{ row.gap }}</b></template></el-table-column>
          <el-table-column :label="t('recon.slip')" align="right"><template #default="{ row }">{{ row.slipBps == null ? '—' : row.slipBps + ' bps' }}</template></el-table-column>
        </el-table>
      </template>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { strategyApi } from '@/api'

const { t } = useI18n()
const show = ref(false), loading = ref(false), botId = ref(''), days = ref(30), d = ref(null)
const MODE_TYPE = { PAPER: 'info', SHADOW: 'warning', LIVE: 'success' }
const modeRows = computed(() => ['PAPER', 'SHADOW', 'LIVE'].map(m => ({ mode: m, ...(d.value?.modes?.[m] || {}) })))
const ex = computed(() => d.value?.execution || {})
const pr = computed(() => d.value?.promotion || {})
const pc = v => ({ color: v > 0 ? '#26a69a' : v < 0 ? '#ef5350' : '' })

async function load() {
  loading.value = true
  try { d.value = (await strategyApi.reconcile(botId.value, days.value)).data }
  catch (e) { ElMessage.error(e.response?.data?.message || e.message) }
  finally { loading.value = false }
}
function open(id) { botId.value = id; d.value = null; show.value = true; load() }
defineExpose({ open })
</script>

<style scoped>
.sec { margin: 16px 0 8px; font-size: 14px; }
.bars { max-width: 520px; }
.kv { display: flex; justify-content: space-between; padding: 3px 0; border-bottom: 1px dashed var(--el-border-color); }
.kv.total { border-bottom: none; border-top: 1px solid var(--el-border-color); margin-top: 2px; padding-top: 6px; }
.kv-row { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
</style>
