<template>
<el-menu :default-active="$route.path" router class="menu" :collapse="collapse"
           :background-color="menuBg" :text-color="menuText" active-text-color="#ffffff">
    <el-menu-item-group>
      <template #title><span class="grp">{{ t('menu.trade') }}</span></template>
      <el-menu-item index="/chart"><el-icon><TrendCharts /></el-icon><template #title>{{ t('menu.chart') }}</template></el-menu-item>
      <el-menu-item index="/live"><el-icon><Monitor /></el-icon><template #title>{{ t('menu.live') }}</template></el-menu-item>
      <el-menu-item index="/orders"><el-icon><Document /></el-icon><template #title>{{ t('menu.orders') }}</template></el-menu-item>
      <el-menu-item index="/account"><el-icon><Wallet /></el-icon><template #title>{{ t('menu.account') }}</template></el-menu-item>
    </el-menu-item-group>
    <el-menu-item-group>
      <template #title><span class="grp">{{ t('menu.analysis') }}</span></template>
      <el-menu-item index="/"><el-icon><DataLine /></el-icon><template #title>{{ t('menu.dashboard') }}</template></el-menu-item>
      <el-menu-item index="/strategy"><el-icon><SetUp /></el-icon><template #title>{{ t('menu.strategy') }}</template></el-menu-item>
      <el-menu-item index="/backtest"><el-icon><Histogram /></el-icon><template #title>{{ t('menu.backtest') }}</template></el-menu-item>
    </el-menu-item-group>
    <el-menu-item-group>
      <template #title><span class="grp">{{ t('menu.settings') }}</span></template>
      <el-menu-item index="/login"><el-icon><Key /></el-icon><template #title>{{ t('menu.login') }}</template></el-menu-item>
      <el-menu-item index="/risk"><el-icon><Warning /></el-icon><template #title>{{ t('menu.risk') }}</template></el-menu-item>
      <el-menu-item index="/system"><el-icon><Monitor /></el-icon><template #title>{{ t('menu.system') }}</template></el-menu-item>
    </el-menu-item-group>
  </el-menu>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useThemeStore } from '@/store/theme'
defineProps({ collapse: { type: Boolean, default: false } })
const { t } = useI18n()
const theme = useThemeStore()
const menuBg = computed(() => theme.dark ? '#131722' : '#ffffff')
const menuText = computed(() => theme.dark ? '#a9b1c2' : '#303133')
</script>
