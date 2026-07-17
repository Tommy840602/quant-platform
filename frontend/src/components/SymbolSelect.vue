<template>
  <el-select :model-value="modelValue" @update:model-value="v => $emit('update:modelValue', v)" filterable v-bind="$attrs">
    <el-option v-for="s in SYMBOLS" :key="s" :label="s" :value="s">
      <span style="display:flex;align-items:center;gap:6px"><CoinIcon :symbol="s" :size="16" />{{ s }}</span>
    </el-option>
    <template #prefix v-if="modelValue"><CoinIcon :symbol="modelValue" :size="16" /></template>
  </el-select>
</template>
<script setup>
import { SYMBOLS } from '@/config/symbols'
import CoinIcon from './CoinIcon.vue'
defineProps({ modelValue: String })
defineEmits(['update:modelValue'])
</script>
