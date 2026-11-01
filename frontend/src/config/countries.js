// 國家 → 預設語言 / 時區 / 計價幣別。選國家一次帶出三項，使用者仍可各自覆寫。
export const COUNTRIES = [
  { code: 'TW', flag: '🇹🇼', name: '台灣',       lang: 'zh-TW', tz: 'Asia/Taipei',      currency: 'TWD', symbol: 'NT$' },
  { code: 'HK', flag: '🇭🇰', name: '香港',       lang: 'zh-TW', tz: 'Asia/Hong_Kong',   currency: 'HKD', symbol: 'HK$' },
  { code: 'CN', flag: '🇨🇳', name: '中国',       lang: 'zh-CN', tz: 'Asia/Shanghai',    currency: 'CNY', symbol: '¥' },
  { code: 'JP', flag: '🇯🇵', name: '日本',       lang: 'ja',    tz: 'Asia/Tokyo',       currency: 'JPY', symbol: '¥' },
  { code: 'KR', flag: '🇰🇷', name: '대한민국',   lang: 'ko',    tz: 'Asia/Seoul',       currency: 'KRW', symbol: '₩' },
  { code: 'SG', flag: '🇸🇬', name: 'Singapore',  lang: 'en',    tz: 'Asia/Singapore',   currency: 'SGD', symbol: 'S$' },
  { code: 'AU', flag: '🇦🇺', name: 'Australia',  lang: 'en',    tz: 'Australia/Sydney', currency: 'AUD', symbol: 'A$' },
  { code: 'GB', flag: '🇬🇧', name: 'United Kingdom', lang: 'en', tz: 'Europe/London',  currency: 'GBP', symbol: '£' },
  { code: 'DE', flag: '🇩🇪', name: 'Deutschland', lang: 'en',   tz: 'Europe/Berlin',    currency: 'EUR', symbol: '€' },
  { code: 'US', flag: '🇺🇸', name: 'United States', lang: 'en', tz: 'America/New_York', currency: 'USD', symbol: '$' }
]
export const LANGS = [
  { value: 'zh-TW', label: '繁體中文' }, { value: 'zh-CN', label: '简体中文' },
  { value: 'en', label: 'English' }, { value: 'ja', label: '日本語' }, { value: 'ko', label: '한국어' }
]
export const TIMEZONES = [
  'Asia/Taipei', 'Asia/Hong_Kong', 'Asia/Shanghai', 'Asia/Tokyo', 'Asia/Seoul', 'Asia/Singapore', 'Asia/Kolkata', 'Asia/Dubai',
  'Australia/Sydney', 'Europe/London', 'Europe/Berlin', 'Europe/Paris', 'America/New_York', 'America/Chicago', 'America/Los_Angeles', 'UTC'
]
export const CURRENCIES = [
  { code: 'TWD', symbol: 'NT$' }, { code: 'USD', symbol: '$' }, { code: 'HKD', symbol: 'HK$' }, { code: 'CNY', symbol: '¥' },
  { code: 'JPY', symbol: '¥' }, { code: 'KRW', symbol: '₩' }, { code: 'SGD', symbol: 'S$' }, { code: 'AUD', symbol: 'A$' },
  { code: 'GBP', symbol: '£' }, { code: 'EUR', symbol: '€' }
]
