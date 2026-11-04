<template>
  <div>
    <el-card :header="t('risk.s1')">
      <el-form label-width="180px" style="max-width:560px">
        <el-form-item :label="t('risk.s2')">
          <el-input-number v-model="cfg.notional" :min="0" :step="1" />
          <span class="hint">{{ t('risk.s27') }}</span>
          <div v-if="cfg.notional > 0 && cfg.notional < 5" style="color:#f56c6c;font-size:12px;width:100%">{{ t('sizing.riskLow') }}</div>
          <div v-else-if="!cfg.notional" style="color:#e6a23c;font-size:12px;width:100%">{{ t('sizing.riskZero') }}</div>
        </el-form-item>
        <el-form-item :label="t('risk.s3')">
          <el-input-number v-model="cfg.maxNotional" :min="0" :step="5" />
          <span class="hint">{{ t('risk.s28') }}</span>
        </el-form-item>
        <el-form-item :label="t('risk.s4')">
          <el-switch v-model="cfg.balanceCheck" />
          <span class="hint">{{ t('risk.s29') }}</span>
        </el-form-item>
        <el-form-item :label="t('risk.s5')">
          <el-tag :type="cfg.dryRun ? 'success' : 'danger'" size="small">
            {{ cfg.dryRun ? t('risk.s66') : t('risk.s67') }}
          </el-tag>
          <span class="hint">{{ t('risk.s30') }}</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">{{ t('risk.s31') }}</el-button>
          <el-button @click="load">{{ t('risk.s32') }}</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card style="margin-top:16px">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>{{ t('risk.s33') }}</span>
          <span>
            <el-button size="small" @click="loadRisk">{{ t('risk.s34') }}</el-button>
            <el-button size="small" type="warning" plain @click="resetDay">{{ t('risk.s35') }}</el-button>
          </span>
        </div>
      </template>
      <el-descriptions :column="4" border size="small" style="margin-bottom:16px" v-if="st.account">
        <el-descriptions-item :label="t('risk.s6')">{{ st.ordersToday }} / {{ lim.maxDailyOrders || '∞' }}</el-descriptions-item>
        <el-descriptions-item :label="t('risk.s7')">{{ st.notionalToday }} / {{ lim.maxDailyNotional || '∞' }} USDT</el-descriptions-item>
        <el-descriptions-item :label="t('risk.s8')">{{ st.equity ?? '—' }}</el-descriptions-item>
        <el-descriptions-item :label="t('risk.s9')">{{ st.dayStartEquity ?? '—' }}</el-descriptions-item>
        <el-descriptions-item :label="t('risk.s10')">
          <span :style="{ color: (st.dayPnlPct ?? 0) >= 0 ? '#67c23a' : '#f56c6c', fontWeight: 600 }">{{ pct(st.dayPnlPct) }}</span>
          <span class="hint">{{ t('risk.s36') }}{{ pct(lim.maxDailyLossPct) }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="t('risk.s11')">{{ pct(st.positionPct) }} <span class="hint">{{ t('risk.s37') }} {{ pct(lim.maxPositionPct) }}</span></el-descriptions-item>
        <el-descriptions-item :label="t('risk.futLev')">{{ st.futuresLeverage == null ? '—' : st.futuresLeverage.toFixed(2) + '×' }}
          <span class="hint">{{ st.futuresGross ?? 0 }} USDT · {{ t('risk.s37') }} {{ lim.maxFuturesLeverage ? lim.maxFuturesLeverage + '×' : '∞' }}</span></el-descriptions-item>
        <el-descriptions-item :label="t('risk.s12')">{{ st.tradingDay }}</el-descriptions-item>
        <el-descriptions-item :label="t('risk.s13')">{{ st.killSwitch ? t('risk.s68') : t('risk.s69') }}</el-descriptions-item>
      </el-descriptions>
      <el-form label-width="180px" style="max-width:560px">
        <el-form-item :label="t('risk.s14')">
          <el-input-number v-model="limForm.maxPositionPct" :min="0" :max="100" :step="5" />
          <span class="hint">{{ t('risk.s38') }}</span>
        </el-form-item>
        <el-form-item :label="t('risk.futLevLimit')">
          <el-input-number v-model="limForm.maxFuturesLeverage" :min="0" :max="20" :step="0.5" :precision="1" />
          <span class="hint">{{ t('risk.futLevHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('risk.s15')">
          <el-input-number v-model="limForm.maxDailyLossPct" :min="0" :max="100" :step="0.5" />
          <span class="hint">{{ t('risk.s39') }}</span>
        </el-form-item>
        <el-form-item :label="t('risk.s16')">
          <el-input-number v-model="limForm.maxDailyOrders" :min="0" :step="5" />
        </el-form-item>
        <el-form-item :label="t('risk.s17')">
          <el-switch v-model="dailyNotionalOn" />
          <el-input-number v-if="dailyNotionalOn" v-model="limForm.maxDailyNotional" :min="1" :step="100" style="margin-left:12px" />
          <span class="hint">{{ t('risk.s40') }}</span>
        </el-form-item>
        <el-form-item :label="t('risk.s18')">
          <el-input-number v-model="limForm.maxPriceDeviationPct" :min="0" :max="100" :step="1" />
          <span class="hint">{{ t('risk.s41') }}</span>
        </el-form-item>
        <el-form-item :label="t('risk.s19')">
          <el-switch v-model="limForm.failClosed" :active-text="t('risk.s20')" :inactive-text="t('risk.s21')" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="savingLim" @click="saveLimits">{{ t('risk.s42') }}</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card :header="t('risk.s22')" style="margin-top:16px">
      <el-form label-width="180px" style="max-width:560px">
        <el-form-item :label="t('risk.s23')">
          <el-tag v-if="!trade.mainnet" type="info" size="small">{{ t('risk.s43') }}</el-tag>
          <el-tag v-else-if="trade.effectiveDryRun" type="info" size="small">{{ t('risk.s44') }}</el-tag>
          <el-tag v-else-if="trade.armUnlimited" type="danger" effect="dark" size="small">{{ t('risk.s45') }}</el-tag>
          <el-tag v-else type="danger" effect="dark" size="small">{{ t('risk.s46') }} {{ Math.floor((trade.armRemainingSec || 0) / 60) }} {{ t('risk.s47') }}</el-tag>
        </el-form-item>
        <el-form-item :label="t('risk.s24')">
          <el-switch v-model="armTtlOn" />
          <template v-if="armTtlOn">
            <el-input-number v-model="armTtlHours" :min="0.5" :max="720" :step="1" style="margin-left:12px" /> <span class="hint">{{ t('risk.s48') }}</span>
          </template>
          <span v-else class="hint">{{ t('risk.s49') }}</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="savingArm" @click="saveArmTtl">{{ t('risk.s50') }}</el-button>
          <el-button v-if="trade.mainnet && !trade.effectiveDryRun && !trade.armUnlimited" @click="extendArm">{{ t('risk.s51') }}</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card :header="t('risk.s25')" style="margin-top:16px">
      <el-form label-width="180px" style="max-width:560px">
        <el-form-item :label="t('risk.s26')">
          <el-tag :type="kill.on ? 'danger' : 'success'">{{ kill.on ? t('risk.s70') : t('risk.s69') }}</el-tag>
          <span v-if="kill.on && kill.reason" class="hint">{{ t('risk.s52') }}{{ kill.reason }}</span>
        </el-form-item>
        <el-form-item>
          <el-button :type="kill.on ? 'success' : 'danger'" :loading="killing" @click="toggleKill">
            {{ kill.on ? t('risk.s71') : t('risk.s72') }}
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
import { reactive, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { strategyApi, orderApi, riskApi, tradeConfigApi } from '@/api'
import { storeToRefs } from 'pinia'
import { useTradeStore } from '@/store/trade'

const saving = ref(false), killing = ref(false)
const cfg = reactive({ notional: 0, maxNotional: 1000, balanceCheck: false, dryRun: true })
const kill = reactive({ on: false, reason: '' })
const tradeStore = useTradeStore(); const { cfg: trade } = storeToRefs(tradeStore)
const armTtlOn = ref(true), armTtlHours = ref(8), savingArm = ref(false)
async function loadArm() {
  await tradeStore.load()
  const h = Number(trade.value.armTtlHours ?? 8)
  armTtlOn.value = h > 0; if (h > 0) armTtlHours.value = h
}
async function saveArmTtl() {
  savingArm.value = true
  try {
    const r = await tradeConfigApi.setArmTtl(armTtlOn.value ? armTtlHours.value : 0)
    if (r.code && r.code !== 0) throw new Error(r.message)
    trade.value = r.data; ElMessage.success(armTtlOn.value ? t('risk.s73', { h: armTtlHours.value }) : t('risk.s53'))
  } catch (e) { ElMessage.error(t('risk.s54') + (e?.message || '')) }
  finally { savingArm.value = false }
}
async function extendArm() {
  try { const r = await tradeConfigApi.arm(); if (r.code && r.code !== 0) throw new Error(r.message); trade.value = r.data; ElMessage.success(t('risk.s55')) }
  catch (e) { ElMessage.error(t('risk.s56') + (e?.message || '')) }
}
const st = reactive({}); const lim = reactive({}); const savingLim = ref(false)
const limForm = reactive({ maxFuturesLeverage: 1, maxPositionPct: 50, maxDailyLossPct: 3, maxDailyOrders: 50, maxDailyNotional: 0, maxPriceDeviationPct: 5, failClosed: true })
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
    limForm.maxFuturesLeverage = lim.maxFuturesLeverage ?? 1
  } catch (e) { ElMessage.error(t('risk.s57') + (e.response?.data?.message || e.message)) }
}
async function saveLimits() {
  savingLim.value = true
  try {
    await riskApi.setLimits({ maxPositionPct: limForm.maxPositionPct / 100, maxDailyLossPct: limForm.maxDailyLossPct / 100,
      maxDailyOrders: limForm.maxDailyOrders, maxDailyNotional: dailyNotionalOn.value ? limForm.maxDailyNotional : 0,
      maxPriceDeviationPct: limForm.maxPriceDeviationPct / 100, failClosed: limForm.failClosed,
      maxFuturesLeverage: limForm.maxFuturesLeverage })
    ElMessage.success(t('risk.s58')); loadRisk()
  } catch (e) { ElMessage.error(t('risk.s54') + (e.response?.data?.message || e.message)) }
  finally { savingLim.value = false }
}
async function resetDay() {
  try { await riskApi.resetDay(); ElMessage.success(t('risk.s59')); loadRisk() }
  catch (e) { ElMessage.error(t('risk.s60')) }
}

async function load() {
  try {
    const [rc, on] = await Promise.all([orderApi.riskConfig(), strategyApi.getOrderNotional()])
    cfg.maxNotional = rc.data?.maxNotionalUsdt ?? cfg.maxNotional
    cfg.balanceCheck = !!rc.data?.balanceCheck
    cfg.dryRun = !!rc.data?.dryRun
    cfg.notional = on.data?.notionalUsdt ?? 0
  } catch (e) { ElMessage.error(t('risk.s61') + (e.response?.data?.message || e.message)) }
  try { const k = await riskApi.getKill(); kill.on = !!k.data?.on; kill.reason = k.data?.reason || '' } catch {}
}

async function save() {
  saving.value = true
  try {
    await orderApi.setRiskConfig({ maxNotionalUsdt: cfg.maxNotional, balanceCheck: cfg.balanceCheck })
    await strategyApi.setOrderNotional(cfg.notional)
    ElMessage.success(t('risk.s62'))
    load()
  } catch (e) { ElMessage.error(t('risk.s54') + (e.response?.data?.message || e.message)) }
  finally { saving.value = false }
}

async function toggleKill() {
  killing.value = true
  try { const k = await riskApi.setKill(!kill.on); kill.on = !!k.data?.on; kill.reason = k.data?.reason || ''
    ElMessage.success(kill.on ? t('risk.s63') : t('risk.s64')) }
  catch (e) { ElMessage.error(t('risk.s65') + (e.response?.data?.message || e.message)) }
  finally { killing.value = false }
}

onMounted(() => { load(); loadRisk(); loadArm() })
</script>

<style scoped>
.hint { color:#999; font-size:12px; margin-left:12px; }
</style>
