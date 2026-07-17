<template>
  <img v-if="!failed" :src="src" :style="box" class="coin-icon" @error="failed = true" alt="" />
  <span v-else class="coin-fallback" :style="box">{{ letter }}</span>
</template>
<script setup>
import { computed, ref, watch } from 'vue'
const props = defineProps({ symbol: { type: String, default: '' }, size: { type: Number, default: 18 } })
const failed = ref(false)
watch(() => props.symbol, () => { failed.value = false })
const base = computed(() => props.symbol.replace(/USDT$|USD$/i, '').toLowerCase())
const src = computed(() => `https://assets.coincap.io/assets/icons/${base.value}@2x.png`)
const letter = computed(() => (base.value[0] || '?').toUpperCase())
const box = computed(() => ({ width: props.size + 'px', height: props.size + 'px' }))
</script>
<style scoped>
.coin-icon { border-radius: 50%; vertical-align: middle; }
.coin-fallback { display: inline-flex; align-items: center; justify-content: center; border-radius: 50%;
  background: #2962ff; color: #fff; font-size: 10px; font-weight: 700; vertical-align: middle; }
</style>
