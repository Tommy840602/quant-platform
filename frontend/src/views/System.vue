<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="24">
        <el-card>
          <template #header>
            <div style="display:flex;justify-content:space-between;align-items:center">
              <span>{{ t('system.s12') }} <el-tag :type="svcSummary.up === svcSummary.total ? 'success' : 'danger'" size="small">{{ svcSummary.up }} / {{ svcSummary.total }} UP</el-tag>
                <span class="hint">{{ t('system.s13') }} {{ lastAt }}</span></span>
              <el-button size="small" :loading="loading" @click="refresh">{{ t('system.s14') }}</el-button>
            </div>
          </template>
          <el-table :data="services" size="small" stripe>
            <el-table-column prop="name" :label="t('system.s1')" width="220" />
            <el-table-column :label="t('system.s2')" width="100">
              <template #default="{ row }"><el-tag :type="row.status === 'UP' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="latencyMs" :label="t('system.s3')" width="100" />
            <el-table-column :label="t('system.s4')">
              <template #default="{ row }">
                <el-tag v-for="(st, k) in row.components" :key="k" :type="st === 'UP' ? 'success' : (st === 'UNKNOWN' ? 'info' : 'danger')" size="small" effect="plain" style="margin-right:4px">{{ compName(k) }}</el-tag>
                <span v-if="row.error" class="hint" style="color:#f56c6c">{{ row.error }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="8">
        <el-card :header="t('system.s5')">
          <div class="kv"><span>{{ t('system.s15') }}</span><b>{{ trade.mainnet ? t('system.s49') : t('system.s50') }}</b></div>
          <div class="kv"><span>{{ t('system.s16') }}</span>
            <el-tag :type="effDry ? 'info' : 'danger'" :effect="effDry ? 'light' : 'dark'" size="small">{{ effDry ? t('system.s51') : t('system.s52') }}</el-tag></div>
          <div class="kv" v-if="trade.mainnet && !effDry"><span>{{ t('system.s17') }}</span><b>{{ trade.armUnlimited ? t('system.s53') : fmtRemain(trade.armRemainingSec) }}</b></div>
          <div class="kv"><span>{{ t('system.s18') }}</span><b>{{ trade.keySet ? t('system.s54') : t('system.s55') }}</b></div>
          <div class="kv"><span>{{ t('system.s19') }}</span>
            <b :style="{ color: Math.abs(trade.clockOffsetMs || 0) > 1000 ? '#f56c6c' : 'inherit' }">{{ trade.clockOffsetMs ?? '—' }} ms</b>
            <span class="hint">{{ trade.clockSyncedAt ? ago(trade.clockSyncedAt) + t('system.s56') : t('system.s57') }}</span></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card :header="t('system.s6')">
          <div v-for="(m, k) in uds" :key="k" class="kv">
            <span>{{ k === 'SPOT' ? t('system.s58') : t('system.s59') }}</span>
            <el-tag :type="m.connected ? 'success' : (m.expected ? 'danger' : 'info')" size="small">{{ m.connected ? t('system.s60') : (m.expected ? t('system.s61') : t('system.s62')) }}</el-tag>
            <span class="hint" v-if="m.expected">{{ m.events }} {{ t('system.s20') }}{{ m.lastEventAt ? t('system.s63') + ago(m.lastEventAt) + t('system.s64') : '' }}{{ m.lastError ? ' · ' + m.lastError : '' }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card :header="t('system.s7')">
          <div class="kv"><span>{{ t('system.s21') }}</span><b>{{ kl.persister?.queued ?? '—' }} / {{ kl.persister?.persisted ?? '—' }} / {{ kl.persister?.dropped ?? '—' }}</b></div>
          <div class="kv"><span>{{ t('system.s22') }}</span><b>{{ kl.loader?.running ? t('system.s65') : (kl.loader?.lastRunAt ? ago(kl.loader.lastRunAt) + t('system.s66') + kl.loader.lastLoaded + t('system.s67') : '—') }}</b></div>
          <div class="kv"><span>{{ t('system.s23') }}</span><b>{{ coverage.symbols }} {{ t('system.s24') }} {{ coverage.minBars }} {{ t('system.s25') }} {{ coverage.staleMin }} {{ t('system.s26') }}</b></div>
          <div class="kv" v-if="kl.persister?.lastError"><span>{{ t('system.s27') }}</span><span class="hint" style="color:#f56c6c">{{ kl.persister.lastError }}</span></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="12">
        <el-card :header="t('system.s8')">
          <div v-if="lag.error" class="hint" style="color:#f56c6c">{{ lag.error }}</div>
          <div v-for="g in (lag.groups || [])" :key="g.group" class="kv">
            <span :title="g.desc">{{ g.group }}</span>
            <el-tag :type="g.lag < 0 ? 'info' : (g.lag > (lag.threshold || 500) ? 'danger' : (g.lag > 50 ? 'warning' : 'success'))" size="small">{{ g.lag < 0 ? '—' : g.lag }}</el-tag>
            <span class="hint">{{ g.note || Object.entries(g.perTopic || {}).map(([t, v]) => t + ':' + v).join(' · ') }}</span>
          </div>
          <div class="hint" v-if="lag.checkedAt">{{ ago(lag.checkedAt) }} {{ t('system.s28') }} {{ lag.threshold }}</div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card :header="t('system.s9')">
          <div class="kv"><span>{{ t('system.s29') }}</span><b>{{ Object.entries(alerts.counts || {}).map(([k, v]) => k + ' ' + v).join(', ') || t('common.none') }}</b></div>
          <div class="kv"><span>{{ t('system.s30') }}</span><b>{{ alerts.suppressedToday ?? '—' }}</b><span class="hint">{{ t('system.s31') }} {{ Math.round((alerts.dedupeWindowSec || 600) / 60) }} {{ t('system.s32') }}</span></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="12">
        <el-card :header="t('system.s10')">
          <div class="kv"><span>{{ t('system.s33') }}</span><el-tag :type="risk.killSwitch ? 'danger' : 'success'" size="small">{{ risk.killSwitch ? t('system.s68') : t('system.s69') }}</el-tag></div>
          <div class="kv"><span>{{ t('system.s34') }}</span><b>{{ risk.ordersToday ?? '—' }} / {{ risk.limits?.maxDailyOrders || '∞' }} · {{ risk.notionalToday ?? '—' }} / {{ risk.limits?.maxDailyNotional || '∞' }} USDT</b></div>
          <div class="kv"><span>{{ t('system.s35') }}</span><b>{{ risk.equity ?? '—' }} / {{ risk.dayStartEquity ?? '—' }}</b></div>
          <div class="kv"><span>{{ t('system.s36') }}</span><b :style="{ color: (risk.dayPnlPct ?? 0) >= 0 ? '#67c23a' : '#f56c6c' }">{{ pct(risk.dayPnlPct) }}</b><span class="hint">{{ t('system.s37') }}{{ pct(risk.limits?.maxDailyLossPct) }}</span></div>
          <div class="kv"><span>{{ t('system.s38') }}</span><b>{{ pct(risk.positionPct) }}</b><span class="hint">{{ t('system.s39') }} {{ pct(risk.limits?.maxPositionPct) }}</span></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card :header="t('system.s11')">
          <div class="kv"><span>{{ t('system.s40') }}</span><b>{{ dd.source ?? '—' }}</b></div>
          <div class="kv"><span>{{ t('system.s41') }}</span><b>{{ num(dd.peak) }} / {{ num(dd.equity) }}</b></div>
          <div class="kv"><span>{{ t('system.s42') }}</span><b :style="{ color: (dd.drawdown ?? 0) < -0.02 ? '#f56c6c' : 'inherit' }">{{ pct(dd.drawdown) }}</b><span class="hint">{{ t('system.s33') }} {{ dd.tripped ? t('system.s68') : t('system.s69') }}</span></div>
          <div class="kv"><span>{{ t('system.s43') }}</span><b>{{ bots.running }} {{ t('system.s44') }} {{ bots.total }}</b>
            <span class="hint">{{ bots.names.join('、') || '—' }}</span></div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import { systemApi, accountApi, riskApi, strategyApi, tradeConfigApi } from '@/api'
import { useTradeStore } from '@/store/trade'
import { useLocaleStore } from '@/store/locale'
const locale = useLocaleStore()

const tradeStore = useTradeStore(); const { cfg: trade } = storeToRefs(tradeStore)
const effDry = computed(() => trade.value.effectiveDryRun ?? trade.value.dryRun)
const services = ref([]); const svcSummary = ref({ up: 0, total: 0 })
const uds = ref({}); const kl = ref({}); const risk = ref({}); const dd = ref({}); const bots = ref({ running: 0, total: 0, names: [] })
const lag = ref({}); const alerts = ref({})
const loading = ref(false); const lastAt = ref('—')

const coverage = computed(() => {
  const rows = kl.value.coverage || []
  if (!rows.length) return { symbols: 0, minBars: 0, staleMin: '—' }
  const minBars = Math.min(...rows.map(r => +r.bars))
  const oldest = Math.min(...rows.map(r => +r.last_ms))
  return { symbols: rows.length, minBars, staleMin: Math.round((Date.now() - oldest) / 60000) }
})
const compName = k => ({ db: 'DB', redis: 'Redis', kafka: 'Kafka', discoveryComposite: 'Nacos', diskSpace: t('system.s45'), ping: 'ping', livenessState: 'live', readinessState: 'ready' }[k] || k)
const pct = v => v == null ? '—' : (v * 100).toFixed(1) + '%'
const num = v => v == null ? '—' : Number(v).toLocaleString('en-US', { maximumFractionDigits: 2 })
const ago = ms => { const s = Math.max(0, Math.round((Date.now() - ms) / 1000)); return s < 60 ? s + t('system.s46') : s < 3600 ? Math.floor(s / 60) + t('system.s47') : Math.floor(s / 3600) + t('system.s48') }
const fmtRemain = s => s >= 3600 ? Math.floor(s / 3600) + 'h' + String(Math.floor(s % 3600 / 60)).padStart(2, '0') + 'm' : Math.floor(s / 60) + 'm'

async function refresh() {
  loading.value = true
  const safe = async (fn, set) => { try { set((await fn()).data) } catch (e) { set(e?.response?.status ? { _err: e.response.status } : {}) } }
  await Promise.all([
    safe(systemApi.status, d => { services.value = d?.services || []; svcSummary.value = d?.summary || { up: 0, total: 0 } }),
    safe(tradeConfigApi.uds, d => { uds.value = d || {} }),
    safe(systemApi.klines, d => { kl.value = d || {} }),
    safe(() => riskApi.status(), d => { risk.value = d || {} }),
    safe(accountApi.drawdown, d => { dd.value = d || {} }),
    safe(systemApi.kafkaLag, d => { lag.value = d || {} }),
    safe(systemApi.alertStats, d => { alerts.value = d || {} }),
    safe(strategyApi.list, d => { const l = d || []; bots.value = { total: l.length, running: l.filter(b => b.status === 'RUNNING').length, names: l.filter(b => b.status === 'RUNNING').map(b => b.id || b.name) } }),
    tradeStore.load()
  ])
  lastAt.value = locale.fmtClock()
  loading.value = false
}
let timer
onMounted(() => { refresh(); timer = setInterval(refresh, 10000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.kv { display:flex; align-items:center; gap:10px; padding:6px 0; border-bottom:1px dashed var(--el-border-color-lighter); font-size:13px; }
.kv > span:first-child { color:var(--el-text-color-secondary); min-width:110px; }
.hint { color:#999; font-size:12px; margin-left:8px; }
</style>
