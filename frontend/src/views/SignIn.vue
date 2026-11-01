<template>
  <div class="signin-wrap">
    <div class="tools">
      <el-button size="small" text @click="pref?.open()" :title="t('locale.title')" style="font-size:16px;padding:0 6px">{{ countryFlag }} <span class="tz">{{ locale.tzOffsetLabel }}</span></el-button>
      <el-button circle size="small" @click="theme.toggle()" :title="theme.dark ? t('header.themeLight') : t('header.themeDark')">
        <el-icon><Sunny v-if="theme.dark" /><Moon v-else /></el-icon>
      </el-button>
    </div>
    <LocaleDialog ref="pref" />
    <el-card class="signin-card">
      <template #header>
        <div class="hd"><span class="logo">{{ t('signin.s3') }}</span><span class="sub">{{ t('signin.s4') }}</span></div>
      </template>
      <el-form :model="form" label-position="top" @submit.prevent="submit">
        <el-form-item :label="t('signin.s1')">
          <el-input v-model="form.username" autocomplete="username" autofocus />
        </el-form-item>
        <el-form-item :label="t('signin.s2')">
          <el-input v-model="form.password" type="password" show-password autocomplete="current-password"
                    @keyup.enter="submit" />
        </el-form-item>
        <el-alert v-if="error" :title="error" type="error" :closable="false" class="mb" />
        <el-button type="primary" native-type="submit" :loading="loading" style="width:100%">{{ t('signin.s4') }}</el-button>
      </el-form>
      <p class="hint">{{ t('signin.s5') }} <code>ADMIN_USERNAME</code> / <code>ADMIN_PASSWORD</code> {{ t('signin.s6') }}</p>
    </el-card>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
import { reactive, ref, computed } from 'vue'
import { Sunny, Moon } from '@element-plus/icons-vue'
import { useThemeStore } from '@/store/theme'
import { useLocaleStore } from '@/store/locale'
import { COUNTRIES } from '@/config/countries'
import LocaleDialog from '@/components/LocaleDialog.vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const theme = useThemeStore()
const locale = useLocaleStore()
const pref = ref(null)
const countryFlag = computed(() => COUNTRIES.find(c => c.code === locale.country)?.flag || '🌐')
const form = reactive({ username: '', password: '' })
const loading = ref(false)
const error = ref('')

async function submit() {
  if (!form.username || !form.password) { error.value = t('signin.s7'); return }
  loading.value = true; error.value = ''
  try {
    await auth.login(form.username, form.password)
    const to = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') ? route.query.redirect : '/'
    router.replace(to)
  } catch (e) {
    error.value = e.response?.status === 401 ? t('signin.s8') : (e.message || t('signin.s9'))
  } finally { loading.value = false }
}
</script>

<style scoped>
.tools { position: fixed; top: 14px; right: 16px; display: flex; align-items: center; gap: 6px; }
.tz { font-size: 11px; color: var(--el-text-color-secondary); }
.signin-wrap { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: var(--el-bg-color-page); padding: 20px; }
.signin-card { width: 380px; }
.hd { display: flex; align-items: baseline; gap: 10px; }
.logo { font-weight: 700; font-size: 16px; }
.sub { color: var(--el-text-color-secondary); font-size: 13px; }
.mb { margin-bottom: 12px; }
.hint { margin: 14px 0 0; font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.6; }
.hint code { font-size: 11px; }
</style>
