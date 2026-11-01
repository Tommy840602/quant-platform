import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { COUNTRIES, CURRENCIES } from '@/config/countries'

const KEY = 'qp-locale'
const load = () => { try { return JSON.parse(localStorage.getItem(KEY) || '{}') } catch { return {} } }

/** 地區偏好：國家 → 語言 / 時區 / 幣別（可各自覆寫）。全站時間、本幣估值、介面語言都從這裡取。 */
export const useLocaleStore = defineStore('locale', () => {
  const saved = load()
  const guessTz = Intl.DateTimeFormat().resolvedOptions().timeZone
  const guessCountry = COUNTRIES.find(c => c.tz === guessTz)?.code || 'TW'
  const country = ref(saved.country || guessCountry)
  const def = () => COUNTRIES.find(c => c.code === country.value) || COUNTRIES[0]
  const lang = ref(saved.lang || def().lang)
  const tz = ref(saved.tz || def().tz)
  const currency = ref(saved.currency || def().currency)
  const rates = ref(saved.rates || {})          // USD 基準匯率快取
  const ratesAt = ref(saved.ratesAt || 0)

  const symbol = computed(() => CURRENCIES.find(c => c.code === currency.value)?.symbol || currency.value + ' ')
  const rate = computed(() => currency.value === 'USD' ? 1 : (rates.value[currency.value] || 0))   // 1 USDT ≈ 1 USD

  /** 選國家：三項一起帶（覆寫掉先前的個別設定）。 */
  function applyCountry(code) {
    const c = COUNTRIES.find(x => x.code === code); if (!c) return
    country.value = c.code; lang.value = c.lang; tz.value = c.tz; currency.value = c.currency
  }
  function persist() {
    localStorage.setItem(KEY, JSON.stringify({ country: country.value, lang: lang.value, tz: tz.value, currency: currency.value, rates: rates.value, ratesAt: ratesAt.value }))
  }
  watch([country, lang, tz, currency], persist)

  async function loadRates(force = false) {
    if (!force && Date.now() - ratesAt.value < 6 * 3600_000 && Object.keys(rates.value).length) return
    try {
      const r = await fetch('https://open.er-api.com/v6/latest/USD').then(x => x.json())
      if (r?.rates) { rates.value = r.rates; ratesAt.value = Date.now(); persist() }
    } catch {}
  }

  /** 時間格式化（跟時區走）。 */
  const fmtTime = (ms, opts = {}) => ms ? new Intl.DateTimeFormat(lang.value, { timeZone: tz.value, hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', ...opts }).format(new Date(ms)) : '—'
  const fmtClock = (ms = Date.now()) => new Intl.DateTimeFormat(lang.value, { timeZone: tz.value, hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(ms))
  /** USDT → 本幣。 */
  const fmtMoney = (usdt, digits) => {
    if (usdt == null || !rate.value) return '—'
    const v = Number(usdt) * rate.value
    const d = digits ?? (['JPY', 'KRW'].includes(currency.value) ? 0 : v >= 1000 ? 0 : 2)
    return symbol.value + v.toLocaleString(lang.value, { maximumFractionDigits: d, minimumFractionDigits: 0 })
  }
  const tzOffsetLabel = computed(() => {
    const p = new Intl.DateTimeFormat('en', { timeZone: tz.value, timeZoneName: 'shortOffset' }).formatToParts(new Date()).find(x => x.type === 'timeZoneName')
    return p ? p.value : ''
  })

  return { country, lang, tz, currency, rates, ratesAt, symbol, rate, tzOffsetLabel, applyCountry, loadRates, fmtTime, fmtClock, fmtMoney }
})
