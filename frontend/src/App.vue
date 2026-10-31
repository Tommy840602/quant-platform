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
          <el-radio-group :model-value="account.mode" size="small" @change="switchMode" :title="'目前：' + account.label">
            <el-radio-button value="paper">虛擬帳戶</el-radio-button>
            <el-radio-button value="main">真實帳戶</el-radio-button>
          </el-radio-group>
          <el-tag v-if="!account.isPaper" type="danger" size="small" effect="dark">真錢 · {{ account.modeInfo.env || '' }}</el-tag>
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
import { useAccountStore } from '@/store/account'
import { ElMessage, ElMessageBox } from 'element-plus'

const router = useRouter()
const market = useMarketStore()
const auth = useAuthStore()
const theme = useThemeStore()
const account = useAccountStore()
const collapsed = ref(localStorage.getItem('qp-collapsed') === '1')
function toggleCollapse() { collapsed.value = !collapsed.value; localStorage.setItem('qp-collapsed', collapsed.value ? '1' : '0') }
const { connected: wsConnected } = storeToRefs(market)

const menuBg = computed(() => theme.dark ? '#131722' : '#ffffff')
const menuText = computed(() => theme.dark ? '#a9b1c2' : '#303133')

onMounted(() => { market.connect(); if (auth.loggedIn) account.loadMode() })

/** 虛擬 ⇄ 真實：切真實要二次確認；後端沒金鑰會拒絕並留在虛擬。 */
async function switchMode(m) {
  if (m === account.mode) return
  if (m === 'main') {
    try {
      await ElMessageBox.confirm(
        '切到真實帳戶後，手動下單與所有策略機器人都會用幣安帳戶（' + (account.modeInfo.env || 'testnet') + '）真實送單。確定？',
        '切換真實帳戶', { type: 'warning', confirmButtonText: '確定切換', cancelButtonText: '取消' })
    } catch { return }
  }
  try {
    const r = await account.setMode(m)
    ElMessage.success('已切換到' + r.label)
  } catch (e) {
    ElMessage.error(e?.message || '切換失敗')
    account.loadMode()
  }
}
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
