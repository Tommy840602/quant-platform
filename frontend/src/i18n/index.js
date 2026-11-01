import { createI18n } from 'vue-i18n'
import zhTW from './zh-TW'
import zhCN from './zh-CN'
import en from './en'
import ja from './ja'
import ko from './ko'
import de from './de'

export const i18n = createI18n({
  legacy: false, locale: 'zh-TW', fallbackLocale: 'zh-TW',
  messages: { 'zh-TW': zhTW, 'zh-CN': zhCN, en, ja, ko, de }
})

// Element Plus 內建語言包（表格空狀態、分頁、日期選擇器等）
const elLocales = {
  'zh-TW': () => import('element-plus/dist/locale/zh-tw.mjs'),
  'zh-CN': () => import('element-plus/dist/locale/zh-cn.mjs'),
  en: () => import('element-plus/dist/locale/en.mjs'),
  ja: () => import('element-plus/dist/locale/ja.mjs'),
  ko: () => import('element-plus/dist/locale/ko.mjs'),
  de: () => import('element-plus/dist/locale/de.mjs')
}
export async function loadElLocale(lang) { return (await (elLocales[lang] || elLocales['zh-TW'])()).default }
