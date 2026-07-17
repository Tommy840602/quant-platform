<template>
  <span class="price-change" :class="up ? 'up' : 'dn'">
    <span class="arrow">{{ up ? '▲' : '▼' }}</span>
    {{ priceText }}
    <span v-if="showPct" class="pct">{{ pctText }}</span>
  </span>
</template>
<script setup>
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useMarketStore } from '@/store/market'
const props = defineProps({ symbol: String, showPct: { type: Boolean, default: true } })
const { stats } = storeToRefs(useMarketStore())
const st = computed(() => stats.value[props.symbol] || {})
const up = computed(() => (st.value.changePct ?? 0) >= 0)
const priceText = computed(() => st.value.price == null ? '—'
  : Number(st.value.price).toLocaleString(undefined, { maximumFractionDigits: 6 }))
const pctText = computed(() => st.value.changePct == null ? ''
  : (st.value.changePct >= 0 ? '+' : '') + st.value.changePct.toFixed(2) + '%')
</script>
<style scoped>
.price-change { font-variant-numeric: tabular-nums; font-weight: 600; }
.price-change.up { color: #26a69a; } .price-change.dn { color: #ef5350; }
.arrow { font-size: 10px; } .pct { font-size: 12px; margin-left: 4px; opacity: .9; }
</style>
