<template>
  <!-- 地區與偏好：國家 → 語言 / 時區 / 幣別（App 與登入頁共用） -->
  <el-dialog v-model="pref.show" :title="t('locale.title')" width="520px">
    <el-form label-width="110px">
      <el-form-item :label="t('locale.country')">
        <el-select v-model="pref.country" style="width:100%" @change="pref.applyCountry">
          <el-option v-for="c in COUNTRIES" :key="c.code" :value="c.code" :label="c.flag + ' ' + c.name + '（' + c.lang + ' · ' + c.tz + ' · ' + c.currency + '）'" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('locale.lang')">
        <el-select v-model="pref.lang" style="width:100%"><el-option v-for="l in LANGS" :key="l.value" :value="l.value" :label="l.label" /></el-select>
      </el-form-item>
      <el-form-item :label="t('locale.tz')">
        <el-select v-model="pref.tz" filterable allow-create style="width:100%"><el-option v-for="z in TIMEZONES" :key="z" :value="z" :label="z" /></el-select>
        <span class="hint">{{ t('locale.now') }}：{{ pref.preview }}</span>
      </el-form-item>
      <el-form-item :label="t('locale.currency')">
        <el-select v-model="pref.currency" style="width:160px"><el-option v-for="c in CURRENCIES" :key="c.code" :value="c.code" :label="c.code + ' ' + c.symbol" /></el-select>
        <span class="hint">{{ t('locale.rate') }} {{ pref.rateText }} <el-button link size="small" @click="locale.loadRates(true)">{{ t('locale.refreshRate') }}</el-button></span>
      </el-form-item>
    </el-form>
    <div class="hint" style="line-height:1.6">{{ t('locale.hint') }}</div>
    <template #footer>
      <el-button @click="pref.show = false">{{ t('locale.cancel') }}</el-button>
      <el-button type="primary" @click="pref.save">{{ t('locale.save') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useLocaleStore } from '@/store/locale'
import { COUNTRIES, LANGS, TIMEZONES, CURRENCIES } from '@/config/countries'

const { t } = useI18n()
const locale = useLocaleStore()
const pref = reactive({
  show: false, country: locale.country, lang: locale.lang, tz: locale.tz, currency: locale.currency,
  get preview() { try { return new Intl.DateTimeFormat(this.lang, { timeZone: this.tz, hour12: false, dateStyle: 'medium', timeStyle: 'medium' }).format(new Date()) } catch { return '—' } },
  get rateText() { const r = this.currency === 'USD' ? 1 : locale.rates[this.currency]; return r ? r.toFixed(3) + ' ' + this.currency : '…' },
  open() { this.country = locale.country; this.lang = locale.lang; this.tz = locale.tz; this.currency = locale.currency; this.show = true; locale.loadRates() },
  applyCountry(code) { const c = COUNTRIES.find(x => x.code === code); if (c) { this.lang = c.lang; this.tz = c.tz; this.currency = c.currency } },
  save() { locale.country = this.country; locale.lang = this.lang; locale.tz = this.tz; locale.currency = this.currency; locale.loadRates(); this.show = false; ElMessage.success(t('locale.saved')) }
})
defineExpose({ open: () => pref.open() })
</script>

<style scoped>
.hint { font-size: 12px; color: var(--el-text-color-secondary); margin-left: 8px; }
</style>
