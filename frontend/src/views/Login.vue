<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2 class="title">{{ t('login.s8') }}</h2>
      <p class="sub">{{ t('login.s9') }}</p>

      <el-form label-position="top">
        <el-form-item :label="t('login.s1')">
          <el-radio-group v-model="cfg.env" style="width:100%">
            <el-radio-button value="testnet">{{ t('login.s10') }}</el-radio-button>
            <el-radio-button value="mainnet">{{ t('login.s11') }}</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="API Key">
          <el-input v-model="cfg.apiKey" :placeholder="t('login.s2')" clearable />
        </el-form-item>
        <el-form-item label="API Secret">
          <el-input v-model="cfg.apiSecret" type="password" :placeholder="t('login.s2')" show-password clearable />
        </el-form-item>

        <el-divider content-position="left">{{ t('login.futTitle') }}</el-divider>
        <el-form-item>
          <el-checkbox v-model="cfg.futuresEnabled">{{ t('login.futEnable') }}</el-checkbox>
          <el-tag v-if="status?.ord" :type="status.ord.futuresKeySet ? 'success' : 'info'" size="small" style="margin-left:8px">
            {{ status.ord.futuresKeySet ? t('login.futKeyOk') : t('login.futKeyNone') }}</el-tag>
        </el-form-item>
        <template v-if="cfg.futuresEnabled">
          <el-form-item :label="t('login.futKey')">
            <el-input v-model="cfg.futuresApiKey" :placeholder="t('login.futKeyPh')" clearable />
          </el-form-item>
          <el-form-item :label="t('login.futSecret')">
            <el-input v-model="cfg.futuresApiSecret" type="password" :placeholder="t('login.futKeyPh')" show-password clearable />
          </el-form-item>
          <div class="hint" style="margin:-8px 0 12px">{{ cfg.env === 'mainnet' ? t('login.futHintMain') : t('login.futHintTest') }}</div>
        </template>
        <div v-else class="hint" style="margin:-8px 0 12px">{{ t('login.futOffHint') }}</div>

        <el-form-item :label="t('login.s3')">
          <el-switch v-model="cfg.dryRun" :active-text="t('login.s4')" :inactive-text="t('login.s5')" inline-prompt
                     style="--el-switch-on-color:#26a69a;--el-switch-off-color:#ef5350" />
        </el-form-item>

        <el-alert v-if="cfg.env==='mainnet' && !cfg.dryRun" type="error" :closable="false" show-icon style="margin-bottom:12px">
          <template #title>{{ t('login.s12') }}</template>
          {{ t('login.s13') }}{{ status?.ord?.armTtlHours ?? 8 }} {{ t('login.s14') }}
          <span v-if="status?.ord?.armed">{{ t('login.s15') }} {{ Math.floor((status.ord.armRemainingSec || 0) / 60) }} {{ t('login.s16') }}
            <el-button link type="primary" size="small" @click="extendArm">{{ t('login.s17') }}</el-button></span>
        </el-alert>
        <el-alert v-else-if="cfg.env==='mainnet'" type="warning" :closable="false" show-icon
          :title="t('login.s6')" style="margin-bottom:12px" />
        <el-alert v-else type="success" :closable="false" show-icon
          :title="t('login.s7')" style="margin-bottom:12px" />

        <el-button type="primary" style="width:100%" :loading="loading" @click="apply">{{ t('login.s18') }}</el-button>
      </el-form>

      <div v-if="status" class="status">
        {{ t('login.s19') }}<b>{{ status.acc?.env }}</b> keySet={{ status.acc?.keySet }} futures={{ status.ord?.futuresEnabled ? (status.ord?.futuresKeySet ? 'on' : 'on/no-key') : 'off' }} {{ t('login.s20') }}<b>{{ status.ord?.env }}</b> dryRun={{ status.ord?.dryRun }}
        <span v-if="status.ord?.mainnet">{{ t('login.s21') }} {{ status.ord?.armed ? t('login.s31') + Math.floor((status.ord.armRemainingSec || 0) / 60) + t('login.s32') : t('login.s33') }}</span>
      </div>
      <p class="hint">{{ t('login.s22') }}</p>
    </el-card>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
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
  futuresEnabled: true, futuresApiKey: '', futuresApiSecret: '',
  dryRun: (localStorage.getItem('qp-dryrun') ?? 'true') === 'true'
})

async function loadStatus() {
  try {
    const [acc, ord] = await Promise.all([accountApi.getConfig(), tradeConfigApi.get()])
    status.value = { acc: acc.data, ord: ord.data }
    if (acc.data?.env) cfg.env = acc.data.env
    if (typeof ord.data?.dryRun === 'boolean') cfg.dryRun = ord.data.dryRun
    if (typeof ord.data?.futuresEnabled === 'boolean') cfg.futuresEnabled = ord.data.futuresEnabled
  } catch { status.value = null }
}

async function extendArm() {
  try { const r = await tradeConfigApi.arm(); if (r.code && r.code !== 0) throw new Error(r.message); status.value = { ...(status.value || {}), ord: r.data }; ElMessage.success(t('login.s23')) }
  catch (e) { ElMessage.error(t('login.s24') + (e?.message || '')) }
}

async function apply() {
  if (cfg.env === 'mainnet' && !cfg.dryRun) {
    try {
      await ElMessageBox.confirm(t('login.s25'),
        t('login.s26'), { type: 'warning', confirmButtonText: t('login.s27'), cancelButtonText: t('login.s28') })
    } catch { return }
  }
  loading.value = true
  try {
    const body = { env: cfg.env, apiKey: cfg.apiKey, apiSecret: cfg.apiSecret,
      futuresApiKey: cfg.futuresEnabled ? cfg.futuresApiKey : '', futuresApiSecret: cfg.futuresEnabled ? cfg.futuresApiSecret : '' }
    await accountApi.setConfig(body)
    await tradeStore.save({ ...body, dryRun: cfg.dryRun, futuresEnabled: cfg.futuresEnabled })   // 寫進共用 store，右上角 / 帳戶頁立即同步
    localStorage.setItem('qp-env', cfg.env)
    localStorage.setItem('qp-dryrun', String(cfg.dryRun))
    ElMessage.success(t('login.s29'))
    router.push('/account')
  } catch (e) {
    ElMessage.error(t('login.s30') + (e.response?.data?.message || e.message))
  } finally { loading.value = false }
}

onMounted(loadStatus)
</script>

<style scoped>
.login-wrap { min-height: calc(100vh - 100px); display: flex; align-items: center; justify-content: center; background: var(--el-bg-color-page); padding: 20px; }
.login-card { width: 420px; }
.title { text-align: center; color: var(--el-color-primary); margin: 0 0 4px; }
.sub { text-align: center; color: var(--el-text-color-secondary); margin: 0 0 20px; font-size: 13px; }
.status { font-size: 12px; color: var(--el-text-color-regular); margin-top: 12px; padding: 8px; background: var(--el-fill-color-light); border-radius: 4px; }
.hint { color: var(--el-text-color-placeholder); font-size: 11px; margin-top: 12px; }
.login-card :deep(.el-divider__text) { background: var(--el-bg-color-overlay); }
</style>
