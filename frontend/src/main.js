import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
// 只註冊有用到的圖示（以前整包 300 個全註冊）
import { DataLine, Document, Expand, Fold, Histogram, Key, Monitor, Moon, SetUp, Sunny, TrendCharts, Wallet, Warning } from '@element-plus/icons-vue'
const ElIcons = { DataLine, Document, Expand, Fold, Histogram, Key, Monitor, Moon, SetUp, Sunny, TrendCharts, Wallet, Warning }
import App from './App.vue'
import router from './router'
import CoinIcon from './components/CoinIcon.vue'
import PriceChange from './components/PriceChange.vue'
import SymbolSelect from './components/SymbolSelect.vue'
import './styles/theme.css'
import './styles/responsive.css'
import { i18n } from './i18n'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(i18n)
for (const [name, comp] of Object.entries(ElIcons)) app.component(name, comp)
app.component('CoinIcon', CoinIcon)
app.component('PriceChange', PriceChange)
app.component('SymbolSelect', SymbolSelect)
app.mount('#app')
