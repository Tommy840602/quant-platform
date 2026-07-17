import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import * as ElIcons from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import CoinIcon from './components/CoinIcon.vue'
import PriceChange from './components/PriceChange.vue'
import SymbolSelect from './components/SymbolSelect.vue'
import './styles/theme.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
for (const [name, comp] of Object.entries(ElIcons)) app.component(name, comp)
app.component('CoinIcon', CoinIcon)
app.component('PriceChange', PriceChange)
app.component('SymbolSelect', SymbolSelect)
app.mount('#app')
