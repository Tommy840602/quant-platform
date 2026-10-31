<template>
  <div>
    <el-card header="下單風控（即時生效，免重啟）">
      <el-form label-width="180px" style="max-width:560px">
        <el-form-item label="每單名目金額 (USDT)">
          <el-input-number v-model="cfg.notional" :min="0" :step="1" />
          <span class="hint">策略每筆目標金額；0 = 用策略原始固定數量。自動換算成各幣數量。</span>
        </el-form-item>
        <el-form-item label="單筆名目上限 (USDT)">
          <el-input-number v-model="cfg.maxNotional" :min="0" :step="5" />
          <span class="hint">超過就拒單；建議 &gt; 每單名目，且 ≥ 幣安 minNotional(5)。</span>
        </el-form-item>
        <el-form-item label="下單前餘額檢查">
          <el-switch v-model="cfg.balanceCheck" />
          <span class="hint">BUY 前查可用 USDT，不足即拒（會多打一次帳戶查詢）。</span>
        </el-form-item>
        <el-form-item label="乾跑模式 (dry-run)">
          <el-tag :type="cfg.dryRun ? 'success' : 'danger'" size="small">
            {{ cfg.dryRun ? '乾跑（不送真單）' : '真實送單' }}
          </el-tag>
          <span class="hint">在「連線設定」頁切換。</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">儲存下單風控</el-button>
          <el-button @click="load">重新載入</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card style="margin-top:16px">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>帳戶風控（risk-service，每筆單送出前檢查）</span>
          <span>
            <el-button size="small" @click="loadRisk">重新整理</el-button>
            <el-button size="small" type="warning" plain @click="resetDay">重置今日計數</el-button>
          </span>
        </div>
      </template>
      <el-descriptions :column="4" border size="small" style="margin-bottom:16px" v-if="st.account">
        <el-descriptions-item label="今日單數">{{ st.ordersToday }} / {{ lim.maxDailyOrders || '∞' }}</el-descriptions-item>
        <el-descriptions-item label="今日名目">{{ st.notionalToday }} / {{ lim.maxDailyNotional || '∞' }} USDT</el-descriptions-item>
        <el-descriptions-item label="目前權益">{{ st.equity ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="日初權益">{{ st.dayStartEquity ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="今日損益">
          <span :style="{ color: (st.dayPnlPct ?? 0) >= 0 ? '#67c23a' : '#f56c6c', fontWeight: 600 }">{{ pct(st.dayPnlPct) }}</span>
          <span class="hint">鎖 -{{ pct(lim.maxDailyLossPct) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="持倉佔比">{{ pct(st.positionPct) }} <span class="hint">上限 {{ pct(lim.maxPositionPct) }}</span></el-descriptions-item>
        <el-descriptions-item label="交易日">{{ st.tradingDay }}</el-descriptions-item>
        <el-descriptions-item label="熔斷">{{ st.killSwitch ? '已觸發' : '正常' }}</el-descriptions-item>
      </el-descriptions>
      <el-form label-width="180px" style="max-width:560px">
        <el-form-item label="持倉佔比上限 (%)">
          <el-input-number v-model="limForm.maxPositionPct" :min="0" :max="100" :step="5" />
          <span class="hint">BUY 後非穩定幣市值 / 總權益；0 = 不限</span>
        </el-form-item>
        <el-form-item label="每日虧損上限 (%)">
          <el-input-number v-model="limForm.maxDailyLossPct" :min="0" :max="100" :step="0.5" />
          <span class="hint">較日初權益；達到後停止買入，賣出減倉不受限</span>
        </el-form-item>
        <el-form-item label="每日下單次數上限">
          <el-input-number v-model="limForm.maxDailyOrders" :min="0" :step="5" />
        </el-form-item>
        <el-form-item label="每日名目總額限制">
          <el-switch v-model="dailyNotionalOn" />
          <el-input-number v-if="dailyNotionalOn" v-model="limForm.maxDailyNotional" :min="1" :step="100" style="margin-left:12px" />
          <span class="hint">正式網預設 1000 USDT；關掉 = 不限（關掉前想清楚）</span>
        </el-form-item>
        <el-form-item label="限價偏離上限 (%)">
          <el-input-number v-model="limForm.maxPriceDeviationPct" :min="0" :max="100" :step="1" />
          <span class="hint">限價與現價差超過就拒（胖手指）</span>
        </el-form-item>
        <el-form-item label="帳戶資料拿不到時">
          <el-switch v-model="limForm.failClosed" active-text="拒絕買入" inactive-text="放行" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="savingLim" @click="saveLimits">儲存帳戶風控</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card header="全域熔斷 (Kill Switch)" style="margin-top:16px">
      <el-form label-width="180px" style="max-width:560px">
        <el-form-item label="狀態">
          <el-tag :type="kill.on ? 'danger' : 'success'">{{ kill.on ? '已熔斷（停止下單）' : '正常' }}</el-tag>
          <span v-if="kill.on && kill.reason" class="hint">原因：{{ kill.reason }}</span>
        </el-form-item>
        <el-form-item>
          <el-button :type="kill.on ? 'success' : 'danger'" :loading="killing" @click="toggleKill">
            {{ kill.on ? '解除熔斷' : '立即熔斷（停止所有下單）' }}
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { strategyApi, orderApi, riskApi } from '@/api'

const saving = ref(false), killing = ref(false)
const cfg = reactive({ notional: 0, maxNotional: 1000, balanceCheck: false, dryRun: true })
const kill = reactive({ on: false, reason: '' })
const st = reactive({}); const lim = reactive({}); const savingLim = ref(false)
const limForm = reactive({ maxPositionPct: 50, maxDailyLossPct: 3, maxDailyOrders: 50, maxDailyNotional: 0, maxPriceDeviationPct: 5, failClosed: true })
const pct = v => v == null ? '—' : (v * 100).toFixed(1) + '%'
const dailyNotionalOn = ref(true)
const lastDailyNotional = ref(1000)

async function loadRisk() {
  try {
    const r = await riskApi.status()
    Object.assign(st, r.data || {}); Object.assign(lim, r.data?.limits || {})
    limForm.maxPositionPct = +(lim.maxPositionPct * 100).toFixed(1)
    limForm.maxDailyLossPct = +(lim.maxDailyLossPct * 100).toFixed(1)
    limForm.maxDailyOrders = lim.maxDailyOrders
    dailyNotionalOn.value = (lim.maxDailyNotional || 0) > 0
    limForm.maxDailyNotional = lim.maxDailyNotional || lastDailyNotional.value
    if (lim.maxDailyNotional > 0) lastDailyNotional.value = lim.maxDailyNotional
    limForm.maxPriceDeviationPct = +(lim.maxPriceDeviationPct * 100).toFixed(1)
    limForm.failClosed = !!lim.failClosed
  } catch (e) { ElMessage.error('讀取帳戶風控失敗：' + (e.response?.data?.message || e.message)) }
}
async function saveLimits() {
  savingLim.value = true
  try {
    await riskApi.setLimits({ maxPositionPct: limForm.maxPositionPct / 100, maxDailyLossPct: limForm.maxDailyLossPct / 100,
      maxDailyOrders: limForm.maxDailyOrders, maxDailyNotional: dailyNotionalOn.value ? limForm.maxDailyNotional : 0,
      maxPriceDeviationPct: limForm.maxPriceDeviationPct / 100, failClosed: limForm.failClosed })
    ElMessage.success('帳戶風控已儲存'); loadRisk()
  } catch (e) { ElMessage.error('儲存失敗：' + (e.response?.data?.message || e.message)) }
  finally { savingLim.value = false }
}
async function resetDay() {
  try { await riskApi.resetDay(); ElMessage.success('今日計數已重置'); loadRisk() }
  catch (e) { ElMessage.error('重置失敗') }
}

async function load() {
  try {
    const [rc, on] = await Promise.all([orderApi.riskConfig(), strategyApi.getOrderNotional()])
    cfg.maxNotional = rc.data?.maxNotionalUsdt ?? cfg.maxNotional
    cfg.balanceCheck = !!rc.data?.balanceCheck
    cfg.dryRun = !!rc.data?.dryRun
    cfg.notional = on.data?.notionalUsdt ?? 0
  } catch (e) { ElMessage.error('讀取風控設定失敗：' + (e.response?.data?.message || e.message)) }
  try { const k = await riskApi.getKill(); kill.on = !!k.data?.on; kill.reason = k.data?.reason || '' } catch {}
}

async function save() {
  saving.value = true
  try {
    await orderApi.setRiskConfig({ maxNotionalUsdt: cfg.maxNotional, balanceCheck: cfg.balanceCheck })
    await strategyApi.setOrderNotional(cfg.notional)
    ElMessage.success('已儲存並即時生效')
    load()
  } catch (e) { ElMessage.error('儲存失敗：' + (e.response?.data?.message || e.message)) }
  finally { saving.value = false }
}

async function toggleKill() {
  killing.value = true
  try { const k = await riskApi.setKill(!kill.on); kill.on = !!k.data?.on; kill.reason = k.data?.reason || ''
    ElMessage.success(kill.on ? '已熔斷' : '已解除') }
  catch (e) { ElMessage.error('操作失敗：' + (e.response?.data?.message || e.message)) }
  finally { killing.value = false }
}

onMounted(() => { load(); loadRisk() })
</script>

<style scoped>
.hint { color:#999; font-size:12px; margin-left:12px; }
</style>
