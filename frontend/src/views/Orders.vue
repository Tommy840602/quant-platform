<template>
  <el-card>
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center">
        <span>{{ t('orders.s10') }}</span>
        <el-button size="small" type="primary" @click="load">{{ t('orders.s11') }}</el-button>
      </div>
    </template>
    <el-form :inline="true" style="margin-bottom:12px">
      <el-form-item :label="t('orders.s1')">
        <symbol-select v-model="filter.symbol" clearable :placeholder="t('orders.s2')" style="width:160px" />
      </el-form-item>
      <el-form-item :label="t('orders.s3')">
        <el-select v-model="filter.status" :placeholder="t('orders.s2')" clearable style="width:150px">
          <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
    </el-form>
    <el-table :data="filtered" size="small" max-height="480">
      <el-table-column prop="clientOrderId" label="ClientOrderId" width="210" />
      <el-table-column :label="t('orders.s1')" width="110">
        <template #default="{ row }">
          <span style="display:inline-flex;align-items:center;gap:6px"><coin-icon v-if="row.symbol && row.symbol !== 'ALL'" :symbol="row.symbol" :size="16" />{{ row.symbol }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="side" :label="t('orders.s4')" width="70" />
      <el-table-column prop="type" :label="t('orders.s5')" width="90" />
      <el-table-column prop="quantity" :label="t('orders.s6')" width="100" />
      <el-table-column prop="filledQty" :label="t('orders.s7')" width="100" />
      <el-table-column prop="status" :label="t('orders.s3')" width="140" />
      <el-table-column prop="env" :label="t('orders.s8')" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.env" :type="row.env.startsWith('mainnet') ? (row.env.endsWith('/dry') ? 'warning' : 'danger') : 'info'" size="small">
            {{ row.env.startsWith('mainnet') ? t('orders.s12') : t('orders.s13') }}{{ row.env.endsWith('/dry') ? t('orders.s14') : '' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="strategyId" :label="t('orders.s9')" width="110" />
      <el-table-column :label="t('orders.time')" width="150">
        <template #default="{ row }">{{ row.createdAt ? locale.fmtTime(row.createdAt, { year: undefined }) : '' }}</template>
      </el-table-column>
      <el-table-column :label="t('orders.reason')" min-width="260">
        <template #default="{ row }"><span v-if="row.reason" :style="{ color: row.status === 'REJECTED' ? '#f56c6c' : '#e6a23c' }">{{ row.reason }}</span></template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
import { useLocaleStore } from '@/store/locale'
const { t } = useI18n()
const locale = useLocaleStore()
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
