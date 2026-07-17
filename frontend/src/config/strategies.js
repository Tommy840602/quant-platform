// 全部策略/機器人類型（與後端 createBot / 回測 factory 一致，共 30 種）
export const STRATEGY_TYPES = [
  { value: 'ma', label: '雙均線 MA' }, { value: 'ema', label: 'EMA 交叉' },
  { value: 'wma', label: 'WMA 交叉' }, { value: 'tema', label: 'TEMA 交叉' },
  { value: 'macd', label: 'MACD' }, { value: 'trix', label: 'TRIX' },
  { value: 'boll', label: '布林(回歸)' }, { value: 'bollbreak', label: '布林突破' },
  { value: 'keltner', label: '肯特納通道' }, { value: 'donchian', label: '唐奇安突破' },
  { value: 'atr', label: 'ATR 通道' }, { value: 'supertrend', label: 'SuperTrend' },
  { value: 'sar', label: 'SAR' }, { value: 'ichimoku', label: '一目均衡' },
  { value: 'kdj', label: 'KDJ' }, { value: 'rsi', label: 'RSI' },
  { value: 'stochrsi', label: 'StochRSI' }, { value: 'cci', label: 'CCI' },
  { value: 'williams', label: '威廉%R' }, { value: 'adx', label: 'ADX/DMI' },
  { value: 'aroon', label: 'Aroon' }, { value: 'roc', label: 'ROC' },
  { value: 'momentum', label: '動量' }, { value: 'zscore', label: 'Z分數回歸' },
  { value: 'vwap', label: 'VWAP' }, { value: 'cmf', label: 'CMF 資金流' },
  { value: 'volbreak', label: '量能突破' }, { value: 'dualthrust', label: 'Dual Thrust' },
  { value: 'grid', label: '網格' }, { value: 'heikin', label: 'Heikin-Ashi' }
]
