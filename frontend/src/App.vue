<template>
  <router-view v-if="$route.meta.public" />

  <el-container v-else class="layout">
    <el-aside :width="collapsed ? '64px' : '210px'" class="aside">
      <div class="aside-header">
        <span class="logo" v-show="!collapsed"></span>
        <span class="logo-mini" v-show="collapsed"></span>
        <el-icon class="collapse-btn" @click="toggleCollapse">
          <Fold v-if="!collapsed" /><Expand v-else />
        </el-icon>
      </div>
      <el-menu :default-active="$route.path" router class="menu" :collapse="collapsed"
               :background-color="menuBg" :text-color="menuText" active-text-color="#ffffff">
        <el-menu-item-group>
          <template #title><span class="grp">交易</span></template>
          <el-menu-item index="/chart"><el-icon><TrendCharts /></el-icon><template #title>即時看盤</template></el-menu-item>
          <el-menu-item index="/live"><el-icon><Monitor /></el-icon><template #title>實盤監控</template></el-menu-item>
          <el-menu-item index="/orders"><el-icon><Document /></el-icon><template #title>訂單紀錄</template></el-menu-item>
          <el-menu-item index="/account"><el-icon><Wallet /></el-icon><template #title>帳戶部位</template></el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group>
          <template #title><span class="grp">分析</span></template>
          <el-menu-item index="/"><el-icon><DataLine /></el-icon><template #title>儀表板</template></el-menu-item>
          <el-menu-item index="/strategy"><el-icon><SetUp /></el-icon><template #title>策略/機器人</template></el-menu-item>
          <el-menu-item index="/backtest"><el-icon><Histogram /></el-icon><template #title>回測結果</template></el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group>
          <template #title><span class="grp">設定</span></template>
          <el-menu-item index="/login"><el-icon><Key /></el-icon><template #title>連線設定</template></el-menu-item>
          <el-menu-item index="/risk"><el-icon><Warning /></el-icon><template #title>風控設定</template></el-menu-item>
        </el-menu-item-group>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="title">{{ $route.meta.title || '量化交易平台' }}</span>
        <div class="right">
          <!-- 全站常駐：env + 是否真送單，避免在正式網關了 Dry-Run 卻沒意識到 -->
          <el-tag v-if="trade.env" :type="trade.mainnet ? (effDry ? 'warning' : 'danger') : 'info'"
                  :effect="trade.mainnet && !effDry ? 'dark' : 'light'" size="small" @click="$router.push('/login')" style="cursor:pointer">
            {{ trade.mainnet ? '正式網' : '測試網' }} · {{ effDry ? 'Dry-Run 不送單' : '真實送單' }}<span v-if="trade.mainnet && !effDry && trade.armRemainingSec > 0"> · 剩 {{ fmtRemain(trade.armRemainingSec) }}</span>
          </el-tag>
          <el-tag :type="wsConnected ? 'success' : 'danger'" size="small">
            {{ wsConnected ? '行情已連線' : '行情斷線' }}
          </el-tag>
          <el-button circle size="small" @click="theme.toggle()" :title="theme.dark ? '切換日間' : '切換夜間'">
            <el-icon><Sunny v-if="theme.dark" /><Moon v-else /></el-icon>
          </el-button>
          <template v-if="auth.loggedIn">
            <span class="user">{{ auth.username }}</span>
            <el-button size="small" text @click="doLogout">登出</el-button>
          </template>
          <el-button v-else size="small" text @click="$router.push('/signin')">登入</el-button>
        </div>
      </el-header>
      <el-main><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useMarketStore } from '@/store/market'
import { useAuthStore } from '@/store/auth'
import { useThemeStore } from '@/store/theme'
import { useTradeStore } from '@/store/trade'
import { watch } from 'vue'

const router = useRouter()
const market = useMarketStore()
const auth = useAuthStore()
const theme = useThemeStore()
const collapsed = ref(localStorage.getItem('qp-collapsed') === '1')
function toggleCollapse() { collapsed.value = !collapsed.value; localStorage.setItem('qp-collapsed', collapsed.value ? '1' : '0') }
const { connected: wsConnected } = storeToRefs(market)

const menuBg = computed(() => theme.dark ? '#131722' : '#ffffff')
const menuText = computed(() => theme.dark ? '#a9b1c2' : '#303133')

const tradeStore = useTradeStore()
const { cfg: trade } = storeToRefs(tradeStore)
// effectiveDryRun = 後端算好的「現在到底會不會真送單」（含上膛時效）；舊後端沒有就退回 dryRun
const effDry = computed(() => trade.value.effectiveDryRun ?? trade.value.dryRun)
const fmtRemain = s => s >= 3600 ? Math.floor(s / 3600) + 'h' + String(Math.floor(s % 3600 / 60)).padStart(2, '0') + 'm' : Math.floor(s / 60) + 'm'
// 開頁、換頁、每 30 秒都重讀；連線設定頁存檔會直接寫進同一個 store
onMounted(() => { market.connect(); if (auth.loggedIn) { tradeStore.load(); setInterval(() => auth.loggedIn && tradeStore.load(), 30000) } })
watch(() => router.currentRoute.value.path, () => { if (auth.loggedIn) tradeStore.load() })
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
.el-main { background: var(--el-bg-color-page); }
</style>
