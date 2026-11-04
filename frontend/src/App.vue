<template>
  <el-config-provider :locale="elLocale">
  <router-view v-if="$route.meta.public" />

  <el-container v-else class="layout">
    <el-aside v-if="!isMobile" :width="collapsed ? '64px' : '210px'" class="aside">
      <div class="aside-header">
        <span class="logo" v-show="!collapsed"></span>
        <span class="logo-mini" v-show="collapsed"></span>
        <el-icon class="collapse-btn" @click="toggleCollapse">
          <Fold v-if="!collapsed" /><Expand v-else />
        </el-icon>
      </div>
      <NavMenu :collapse="collapsed" />
    </el-aside>
    <!-- 手機：側欄改成左側抽屜，點選單或換頁就收起 -->
    <el-drawer v-if="isMobile" v-model="drawer" direction="ltr" size="230px" :with-header="false" class="nav-drawer">
      <div class="aside-header"><span class="logo"></span></div>
      <NavMenu @select="drawer = false" />
    </el-drawer>
    <el-container class="main-col">
      <el-header class="header" :class="{ 'is-mobile': isMobile }">
        <span class="title">
          <el-icon v-if="isMobile" class="burger" @click="drawer = true"><Expand /></el-icon>{{ routeTitle }}
        </span>
        <div class="right">
          <!-- 全站常駐：env + 是否真送單，避免在正式網關了 Dry-Run 卻沒意識到 -->
          <el-tag v-if="trade.env" :type="trade.mainnet ? (effDry ? 'warning' : 'danger') : 'info'"
                  :effect="trade.mainnet && !effDry ? 'dark' : 'light'" size="small" @click="$router.push('/login')" style="cursor:pointer">
            {{ trade.mainnet ? t('header.mainnet') : t('header.testnet') }} · {{ effDry ? t('header.dryRun') : t('header.live') }}<span v-if="trade.mainnet && !effDry && trade.armRemainingSec > 0"> · {{ t('header.remain') }} {{ fmtRemain(trade.armRemainingSec) }}</span>
          </el-tag>
          <!-- 成交回報：UDS 連著 = 即時；設定了但沒連上 = 退回 30 秒輪詢 -->
          <el-tag v-if="udsExpected && !isMobile" :type="udsConnected ? 'success' : 'warning'" size="small" :title="udsTitle">
            {{ udsConnected ? t('header.fillsLive') : t('header.fillsPoll') }}
          </el-tag>
          <el-tag v-if="!isMobile" :type="wsConnected ? 'success' : 'danger'" size="small">
            {{ wsConnected ? t('header.marketOn') : t('header.marketOff') }}
          </el-tag>
          <span v-else class="ws-dot" :class="wsConnected ? 'on' : 'off'" :title="wsConnected ? t('header.marketOn') : t('header.marketOff')"></span>
          <el-button size="small" text @click="pref?.open()" :title="t('locale.title')" style="font-size:16px;padding:0 6px">{{ countryFlag }} <span v-if="!isMobile" style="font-size:11px;color:var(--el-text-color-secondary)">{{ locale.tzOffsetLabel }} {{ clock }}</span></el-button>
          <el-button circle size="small" @click="theme.toggle()" :title="theme.dark ? t('header.themeLight') : t('header.themeDark')">
            <el-icon><Sunny v-if="theme.dark" /><Moon v-else /></el-icon>
          </el-button>
          <template v-if="auth.loggedIn">
            <span v-if="!isMobile" class="user">{{ auth.username }}</span>
            <el-button size="small" text @click="doLogout">{{ t('header.logout') }}</el-button>
          </template>
          <el-button v-else size="small" text @click="$router.push('/signin')">{{ t('header.signin') }}</el-button>
        </div>
      </el-header>
      <el-main><router-view /></el-main>
    </el-container>
  </el-container>

  <LocaleDialog ref="pref" />
  </el-config-provider>
</template>

<script setup>
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useMarketStore } from '@/store/market'
import { useAuthStore } from '@/store/auth'
import { useThemeStore } from '@/store/theme'
import { useTradeStore } from '@/store/trade'
import { tradeConfigApi } from '@/api'
import { useOrderAlerts } from '@/composables/useOrderAlerts'
import { watch, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { useLocaleStore } from '@/store/locale'
import { COUNTRIES } from '@/config/countries'
import { loadElLocale } from '@/i18n'
import LocaleDialog from '@/components/LocaleDialog.vue'
import NavMenu from '@/components/NavMenu.vue'
import { useIsMobile } from '@/composables/useIsMobile'

const router = useRouter()
const { t, locale: i18nLocale } = useI18n()
const locale = useLocaleStore()
const elLocale = ref(null)
const countryFlag = computed(() => COUNTRIES.find(c => c.code === locale.country)?.flag || '🌐')
const clock = ref(''); setInterval(() => { clock.value = locale.fmtClock() }, 1000)
// 路由標題：有翻譯鍵用翻譯，沒有用 meta.title
const ROUTE_KEYS = { '/': 'menu.dashboard', '/chart': 'menu.chart', '/live': 'menu.live', '/orders': 'menu.orders', '/account': 'menu.account', '/strategy': 'menu.strategy', '/backtest': 'menu.backtest', '/login': 'menu.login', '/risk': 'menu.risk', '/system': 'menu.system' }
const routeTitle = computed(() => ROUTE_KEYS[router.currentRoute.value.path] ? t(ROUTE_KEYS[router.currentRoute.value.path]) : (router.currentRoute.value.meta.title || t('header.title')))
async function applyLang(l) { i18nLocale.value = l; document.documentElement.lang = l; elLocale.value = await loadElLocale(l) }
watch(() => locale.lang, applyLang, { immediate: true })

const pref = ref(null)
const market = useMarketStore()
const auth = useAuthStore()
const theme = useThemeStore()
const collapsed = ref(localStorage.getItem('qp-collapsed') === '1')
function toggleCollapse() { collapsed.value = !collapsed.value; localStorage.setItem('qp-collapsed', collapsed.value ? '1' : '0') }
const { connected: wsConnected } = storeToRefs(market)
const isMobile = useIsMobile()
const drawer = ref(false)

const menuBg = computed(() => theme.dark ? '#131722' : '#ffffff')
const menuText = computed(() => theme.dark ? '#a9b1c2' : '#303133')

const tradeStore = useTradeStore()
const { cfg: trade } = storeToRefs(tradeStore)
// effectiveDryRun = 後端算好的「現在到底會不會真送單」（含上膛時效）；舊後端沒有就退回 dryRun
const effDry = computed(() => trade.value.effectiveDryRun ?? trade.value.dryRun)
const fmtRemain = s => s >= 3600 ? Math.floor(s / 3600) + 'h' + String(Math.floor(s % 3600 / 60)).padStart(2, '0') + 'm' : Math.floor(s / 60) + 'm'
// 開頁、換頁、每 30 秒都重讀；連線設定頁存檔會直接寫進同一個 store
const uds = ref({})
async function loadUds() { try { uds.value = (await tradeConfigApi.uds()).data || {} } catch { uds.value = {} } }
const udsExpected = computed(() => Object.values(uds.value).some(m => m?.expected))
const udsConnected = computed(() => Object.entries(uds.value).filter(([, m]) => m?.expected).every(([, m]) => m?.connected))
const udsTitle = computed(() => Object.entries(uds.value).map(([k, m]) => `${k}: ${m?.connected ? '已連線' : (m?.expected ? '未連線 ' + (m?.lastError || '') : '未啟用')}`).join('\n'))
const orderAlerts = useOrderAlerts({ t, router, enabled: () => auth.loggedIn })
watch(() => auth.loggedIn, v => { if (v) orderAlerts.start() })
onMounted(() => { market.connect(); if (auth.loggedIn) { orderAlerts.start(); tradeStore.load(); loadUds(); setInterval(() => { if (auth.loggedIn) { tradeStore.load(); loadUds() } }, 30000) } })
watch(() => router.currentRoute.value.path, () => { drawer.value = false; if (auth.loggedIn) tradeStore.load() })
function doLogout() { auth.logout(); router.push('/signin') }
</script>

<style>
body { margin: 0; }
.layout { height: 100vh; }
.aside { background: v-bind(menuBg); transition: width .2s, background .2s;
  border-right: 1px solid var(--el-border-color); overflow: hidden; }
.aside-header { display: flex; align-items: center; justify-content: space-between;
  height: 56px; padding: 0 16px; border-bottom: 1px solid var(--el-border-color); }
.logo { font-weight: 700; font-size: 15px; letter-spacing: .5px; color: var(--el-text-color-primary); white-space: nowrap; }
.logo-mini { font-size: 18px; }
.collapse-btn { cursor: pointer; color: var(--el-text-color-secondary); font-size: 18px; }
.menu { border: none; }
.menu:not(.el-menu--collapse) { width: 210px; }
.menu .el-menu-item.is-active { background: #2962ff !important; color: #fff !important; }
.menu .el-menu-item.is-active .el-icon { color: #fff !important; }
.grp { font-size: 11px; color: var(--el-text-color-secondary); letter-spacing: 1px; }
.header { display: flex; justify-content: space-between; align-items: center;
  border-bottom: 1px solid var(--el-border-color);
  background: var(--el-bg-color); color: var(--el-text-color-primary); }
.header .title { font-weight: 600; }
.header .right { display: flex; align-items: center; gap: 12px; }
.header .user { color: var(--el-text-color-secondary); font-size: 13px; }
.hint { color: #999; font-size: 12px; margin-left: 8px; }
.el-main { background: var(--el-bg-color-page); }
.main-col { min-width: 0; }
.burger { font-size: 20px; margin-right: 10px; vertical-align: -4px; cursor: pointer; }
.ws-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.ws-dot.on { background: #67c23a; } .ws-dot.off { background: #f56c6c; }
.nav-drawer .el-drawer__body { padding: 0; background: var(--el-bg-color); }
.nav-drawer .menu { width: 100%; }
.header.is-mobile { padding: 0 10px; height: 52px; }
.header.is-mobile .right { gap: 6px; }
.header.is-mobile .title { font-size: 15px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
</style>
<style>
.order-alert .el-notification__content p { white-space: pre-line; cursor: pointer; }
</style>
