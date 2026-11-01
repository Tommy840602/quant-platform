<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="24">
        <el-card>
          <template #header>
            <div style="display:flex;justify-content:space-between;align-items:center">
              <span>服務健康 <el-tag :type="svcSummary.up === svcSummary.total ? 'success' : 'danger'" size="small">{{ svcSummary.up }} / {{ svcSummary.total }} UP</el-tag>
                <span class="hint">每 10 秒刷新 · 上次 {{ lastAt }}</span></span>
              <el-button size="small" :loading="loading" @click="refresh">立即刷新</el-button>
            </div>
          </template>
          <el-table :data="services" size="small" stripe>
            <el-table-column prop="name" label="服務" width="220" />
            <el-table-column label="狀態" width="100">
              <template #default="{ row }"><el-tag :type="row.status === 'UP' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="latencyMs" label="延遲 (ms)" width="100" />
            <el-table-column label="元件">
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
        <el-card header="下單 / 真錢安全鎖">
          <div class="kv"><span>環境</span><b>{{ trade.mainnet ? '正式網（真錢）' : '測試網' }}</b></div>
          <div class="kv"><span>送單</span>
            <el-tag :type="effDry ? 'info' : 'danger'" :effect="effDry ? 'light' : 'dark'" size="small">{{ effDry ? 'Dry-Run 不送單' : '真實送單中' }}</el-tag></div>
          <div class="kv" v-if="trade.mainnet && !effDry"><span>上膛剩餘</span><b>{{ trade.armUnlimited ? '不限時' : fmtRemain(trade.armRemainingSec) }}</b></div>
          <div class="kv"><span>金鑰</span><b>{{ trade.keySet ? '已設定' : '未設定' }}</b></div>
          <div class="kv"><span>幣安對時偏移</span>
            <b :style="{ color: Math.abs(trade.clockOffsetMs || 0) > 1000 ? '#f56c6c' : 'inherit' }">{{ trade.clockOffsetMs ?? '—' }} ms</b>
            <span class="hint">{{ trade.clockSyncedAt ? ago(trade.clockSyncedAt) + ' 前對時' : '尚未對時' }}</span></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card header="成交回報 (User Data Stream)">
          <div v-for="(m, k) in uds" :key="k" class="kv">
            <span>{{ k === 'SPOT' ? '現貨' : '合約' }}</span>
            <el-tag :type="m.connected ? 'success' : (m.expected ? 'danger' : 'info')" size="small">{{ m.connected ? '即時' : (m.expected ? '未連線' : '未啟用 → 30 秒輪詢') }}</el-tag>
            <span class="hint" v-if="m.expected">{{ m.events }} 筆事件{{ m.lastEventAt ? '，最近 ' + ago(m.lastEventAt) + ' 前' : '' }}{{ m.lastError ? ' · ' + m.lastError : '' }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card header="K 線落庫">
          <div class="kv"><span>佇列 / 已寫 / 丟棄</span><b>{{ kl.persister?.queued ?? '—' }} / {{ kl.persister?.persisted ?? '—' }} / {{ kl.persister?.dropped ?? '—' }}</b></div>
          <div class="kv"><span>暖機</span><b>{{ kl.loader?.running ? '進行中' : (kl.loader?.lastRunAt ? ago(kl.loader.lastRunAt) + ' 前完成，補 ' + kl.loader.lastLoaded + ' 根' : '—') }}</b></div>
          <div class="kv"><span>覆蓋</span><b>{{ coverage.symbols }} 幣 · 最少 {{ coverage.minBars }} 根 · 最舊更新 {{ coverage.staleMin }} 分前</b></div>
          <div class="kv" v-if="kl.persister?.lastError"><span>最近錯誤</span><span class="hint" style="color:#f56c6c">{{ kl.persister.lastError }}</span></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="12">
        <el-card header="Kafka 消費落後 (lag)">
          <div v-if="lag.error" class="hint" style="color:#f56c6c">{{ lag.error }}</div>
          <div v-for="g in (lag.groups || [])" :key="g.group" class="kv">
            <span :title="g.desc">{{ g.group }}</span>
            <el-tag :type="g.lag < 0 ? 'info' : (g.lag > (lag.threshold || 500) ? 'danger' : (g.lag > 50 ? 'warning' : 'success'))" size="small">{{ g.lag < 0 ? '—' : g.lag }}</el-tag>
            <span class="hint">{{ g.note || Object.entries(g.perTopic || {}).map(([t, v]) => t + ':' + v).join(' · ') }}</span>
          </div>
          <div class="hint" v-if="lag.checkedAt">{{ ago(lag.checkedAt) }} 前檢查 · 門檻 {{ lag.threshold }}</div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card header="告警 (notification-service)">
          <div class="kv"><span>今日各類</span><b>{{ Object.entries(alerts.counts || {}).map(([k, v]) => k + ' ' + v).join('，') || '無' }}</b></div>
          <div class="kv"><span>重複已壓抑</span><b>{{ alerts.suppressedToday ?? '—' }}</b><span class="hint">同類 {{ Math.round((alerts.dedupeWindowSec || 600) / 60) }} 分鐘一則</span></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="12">
        <el-card header="風控 (risk-service)">
          <div class="kv"><span>熔斷</span><el-tag :type="risk.killSwitch ? 'danger' : 'success'" size="small">{{ risk.killSwitch ? '已觸發' : '正常' }}</el-tag></div>
          <div class="kv"><span>今日單數 / 名目</span><b>{{ risk.ordersToday ?? '—' }} / {{ risk.limits?.maxDailyOrders || '∞' }} · {{ risk.notionalToday ?? '—' }} / {{ risk.limits?.maxDailyNotional || '∞' }} USDT</b></div>
          <div class="kv"><span>權益 / 日初</span><b>{{ risk.equity ?? '—' }} / {{ risk.dayStartEquity ?? '—' }}</b></div>
          <div class="kv"><span>今日損益</span><b :style="{ color: (risk.dayPnlPct ?? 0) >= 0 ? '#67c23a' : '#f56c6c' }">{{ pct(risk.dayPnlPct) }}</b><span class="hint">鎖 -{{ pct(risk.limits?.maxDailyLossPct) }}</span></div>
          <div class="kv"><span>持倉佔比</span><b>{{ pct(risk.positionPct) }}</b><span class="hint">上限 {{ pct(risk.limits?.maxPositionPct) }}</span></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card header="回撤熔斷 (account-service) / 機器人">
          <div class="kv"><span>權益來源</span><b>{{ dd.source ?? '—' }}</b></div>
          <div class="kv"><span>峰值 / 目前</span><b>{{ num(dd.peak) }} / {{ num(dd.equity) }}</b></div>
          <div class="kv"><span>回撤</span><b :style="{ color: (dd.drawdown ?? 0) < -0.02 ? '#f56c6c' : 'inherit' }">{{ pct(dd.drawdown) }}</b><span class="hint">熔斷 {{ dd.tripped ? '已觸發' : '正常' }}</span></div>
          <div class="kv"><span>機器人</span><b>{{ bots.running }} 運作中 / {{ bots.total }}</b>
            <span class="hint">{{ bots.names.join('、') || '—' }}</span></div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import { systemApi, accountApi, riskApi, strategyApi, tradeConfigApi } from '@/api'
import { useTradeStore } from '@/store/trade'

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
const compName = k => ({ db: 'DB', redis: 'Redis', kafka: 'Kafka', discoveryComposite: 'Nacos', diskSpace: '磁碟', ping: 'ping', livenessState: 'live', readinessState: 'ready' }[k] || k)
const pct = v => v == null ? '—' : (v * 100).toFixed(1) + '%'
const num = v => v == null ? '—' : Number(v).toLocaleString('en-US', { maximumFractionDigits: 2 })
const ago = ms => { const s = Math.max(0, Math.round((Date.now() - ms) / 1000)); return s < 60 ? s + ' 秒' : s < 3600 ? Math.floor(s / 60) + ' 分' : Math.floor(s / 3600) + ' 小時' }
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
  lastAt.value = new Date().toLocaleTimeString()
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
