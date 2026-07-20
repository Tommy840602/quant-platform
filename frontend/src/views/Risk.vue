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

onMounted(load)
</script>

<style scoped>
.hint { color:#999; font-size:12px; margin-left:12px; }
</style>
