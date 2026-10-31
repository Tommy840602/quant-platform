<template>
  <el-card>
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>訂單紀錄</span>
        <el-button size="small" type="primary" @click="load">重新整理</el-button>
      </div>
    </template>
    <el-form :inline="true" style="margin-bottom:12px">
      <el-form-item label="標的">
        <symbol-select v-model="filter.symbol" clearable placeholder="全部" style="width:160px" />
      </el-form-item>
      <el-form-item label="狀態">
        <el-select v-model="filter.status" placeholder="全部" clearable style="width:150px">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
    </el-form>
    <el-table :data="filtered" size="small" max-height="480">
      <el-table-column prop="clientOrderId" label="ClientOrderId" width="210" />
      <el-table-column label="標的" width="110">
        <template #default="{ row }">
          <span style="display:inline-flex;align-items:center;gap:6px"><coin-icon v-if="row.symbol && row.symbol !== 'ALL'" :symbol="row.symbol" :size="16" />{{ row.symbol }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="side" label="方向" width="70" />
      <el-table-column prop="type" label="類型" width="90" />
      <el-table-column prop="quantity" label="數量" width="100" />
      <el-table-column prop="filledQty" label="成交量" width="100" />
      <el-table-column prop="status" label="狀態" width="140" />
      <el-table-column prop="account" label="帳戶" width="90">
        <template #default="{ row }">
          <el-tag :type="row.account === 'paper' ? 'warning' : 'success'" size="small">{{ row.account === 'paper' ? '虛擬' : '真實' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="strategyId" label="策略" />
    </el-table>
  </el-card>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { SYMBOLS } from '@/config/symbols'
import { orderApi } from '@/api'

const statuses = ['CREATED', 'RISK_APPROVED', 'SUBMITTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELED', 'REJECTED']
const filter = reactive({ symbol: '', status: '' })
const orders = ref([])

const filtered = computed(() => orders.value.filter(o =>
  (!filter.symbol || o.symbol === filter.symbol) && (!filter.status || o.status === filter.status)))

async function load() {
  try { orders.value = (await orderApi.recent(200)).data || [] } catch { orders.value = [] }
}
onMounted(load)
</script>
