<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2 class="title">量化交易平台</h2>
      <p class="sub">連線設定與登入</p>

      <el-form label-position="top">
        <el-form-item label="環境">
          <el-radio-group v-model="cfg.env" style="width:100%">
            <el-radio-button value="testnet">測試網 testnet（安全）</el-radio-button>
            <el-radio-button value="mainnet">正式網 mainnet（真錢）</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="API Key">
          <el-input v-model="cfg.apiKey" placeholder="留空=沿用目前設定" clearable />
        </el-form-item>
        <el-form-item label="API Secret">
          <el-input v-model="cfg.apiSecret" type="password" placeholder="留空=沿用目前設定" show-password clearable />
        </el-form-item>

        <el-form-item label="下單模式">
          <el-switch v-model="cfg.dryRun" active-text="Dry-Run 乾跑（不送單）" inactive-text="真實送單" inline-prompt
                     style="--el-switch-on-color:#26a69a;--el-switch-off-color:#ef5350" />
        </el-form-item>

        <el-alert v-if="cfg.env==='mainnet' && !cfg.dryRun" type="error" :closable="false" show-icon style="margin-bottom:12px">
          <template #title>⚠️ 正式網 + 真實送單：將用真錢下單，請再三確認</template>
          套用後會「上膛」{{ status?.ord?.armTtlHours ?? 8 }} 小時，到期自動鎖回 Dry-Run 並 Slack 通知。
          <span v-if="status?.ord?.armed">目前剩 {{ Math.floor((status.ord.armRemainingSec || 0) / 60) }} 分鐘
            <el-button link type="primary" size="small" @click="extendArm">延長</el-button></span>
        </el-alert>
        <el-alert v-else-if="cfg.env==='mainnet'" type="warning" :closable="false" show-icon
          title="正式網：看真實帳戶餘額；下單為乾跑，不會動真錢" style="margin-bottom:12px" />
        <el-alert v-else type="success" :closable="false" show-icon
          title="測試網：完全不碰真錢" style="margin-bottom:12px" />

        <el-button type="primary" style="width:100%" :loading="loading" @click="apply">套用並進入</el-button>
      </el-form>

      <div v-if="status" class="status">
        目前後端：帳戶 env=<b>{{ status.acc?.env }}</b> keySet={{ status.acc?.keySet }} ｜
        下單 env=<b>{{ status.ord?.env }}</b> dryRun={{ status.ord?.dryRun }}
        <span v-if="status.ord?.mainnet">｜ 真錢送單 {{ status.ord?.armed ? '已上膛，剩 ' + Math.floor((status.ord.armRemainingSec || 0) / 60) + ' 分鐘' : '鎖定中' }}</span>
      </div>
      <p class="hint">API 金鑰只送到後端記憶體、不寫入瀏覽器。切正式網請先用「只讀」權限金鑰確認餘額。</p>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { accountApi, tradeConfigApi } from '@/api'
import { useTradeStore } from '@/store/trade'

const router = useRouter()
const tradeStore = useTradeStore()
const loading = ref(false)
const status = ref(null)
const cfg = reactive({
  env: localStorage.getItem('qp-env') || 'testnet',
  apiKey: '', apiSecret: '',
  dryRun: (localStorage.getItem('qp-dryrun') ?? 'true') === 'true'
})

async function loadStatus() {
  try {
    const [acc, ord] = await Promise.all([accountApi.getConfig(), tradeConfigApi.get()])
    status.value = { acc: acc.data, ord: ord.data }
    if (acc.data?.env) cfg.env = acc.data.env
    if (typeof ord.data?.dryRun === 'boolean') cfg.dryRun = ord.data.dryRun
  } catch { status.value = null }
}

async function extendArm() {
  try { const r = await tradeConfigApi.arm(); if (r.code && r.code !== 0) throw new Error(r.message); status.value = { ...(status.value || {}), ord: r.data }; ElMessage.success('已延長') }
  catch (e) { ElMessage.error('延長失敗：' + (e?.message || '')) }
}

async function apply() {
  if (cfg.env === 'mainnet' && !cfg.dryRun) {
    try {
      await ElMessageBox.confirm('你選了「正式網 + 真實送單」，之後策略或手動下單會用真錢成交。確定要啟用？',
        '真錢下單確認', { type: 'warning', confirmButtonText: '我了解，啟用', cancelButtonText: '取消' })
    } catch { return }
  }
  loading.value = true
  try {
    const body = { env: cfg.env, apiKey: cfg.apiKey, apiSecret: cfg.apiSecret }
    await accountApi.setConfig(body)
    await tradeStore.save({ ...body, dryRun: cfg.dryRun })   // 寫進共用 store，右上角 / 帳戶頁立即同步
    localStorage.setItem('qp-env', cfg.env)
    localStorage.setItem('qp-dryrun', String(cfg.dryRun))
    ElMessage.success('設定已套用')
    router.push('/account')
  } catch (e) {
    ElMessage.error('套用失敗：' + (e.response?.data?.message || e.message))
  } finally { loading.value = false }
}

onMounted(loadStatus)
</script>

<style scoped>
.login-wrap { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #f0f2f5; padding: 20px; }
.login-card { width: 420px; }
.title { text-align: center; color: #1f4e79; margin: 0 0 4px; }
.sub { text-align: center; color: #888; margin: 0 0 20px; font-size: 13px; }
.status { font-size: 12px; color: #666; margin-top: 12px; padding: 8px; background: #f6f8fa; border-radius: 4px; }
.hint { color: #aaa; font-size: 11px; margin-top: 12px; }
</style>
