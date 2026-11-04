<template>
  <el-card>
    <template #header>
      <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px">
        <span>{{ t('monthly.title') }}</span>
        <div style="display:flex;gap:8px;align-items:center">
          <el-select v-model="months" size="small" style="width:110px" @change="load">
            <el-option v-for="m in [3, 6, 12, 24]" :key="m" :label="t('monthly.lastN', { n: m })" :value="m" />
          </el-select>
          <el-button size="small" :loading="loading" @click="load">{{ t('monthly.refresh') }}</el-button>
          <el-button size="small" type="primary" :loading="downloading" @click="download">{{ t('monthly.csv') }}</el-button>
        </div>
      </div>
    </template>
    <el-table :data="totals" size="small" v-loading="loading" :empty-text="t('monthly.empty')" row-key="month"
              :expand-row-keys="expanded" @expand-change="(r, rows) => expanded = rows.map(x => x.month)">
      <el-table-column type="expand">
        <template #default="{ row }">
          <el-table :data="byMonth[row.month] || []" size="small" style="margin:0 0 0 24px">
            <el-table-column prop="strategy" :label="t('monthly.bot')" min-width="140" />
            <el-table-column :label="t('monthly.market')" width="90"><template #default="{ row: r }">{{ r.market === 'USDT_M' ? t('monthly.fut') : t('monthly.spot') }}</template></el-table-column>
            <el-table-column prop="roundTrips" :label="t('monthly.trips')" align="right" width="80" />
            <el-table-column :label="t('monthly.winRate')" align="right" width="80"><template #default="{ row: r }">{{ r.winRate == null ? '—' : r.winRate + '%' }}</template></el-table-column>
            <el-table-column :label="t('monthly.realized')" align="right"><template #default="{ row: r }"><span :style="pc(r.realizedPnl)">{{ fx(r.realizedPnl) }}</span></template></el-table-column>
            <el-table-column :label="t('monthly.fees')" align="right"><template #default="{ row: r }">−{{ fx(r.fees) }}</template></el-table-column>
            <el-table-column :label="t('monthly.net')" align="right"><template #default="{ row: r }"><b :style="pc(r.netPnl)">{{ fx(r.netPnl) }}</b></template></el-table-column>
          </el-table>
        </template>
      </el-table-column>
      <el-table-column prop="month" :label="t('monthly.month')" width="100" />
      <el-table-column prop="fills" :label="t('monthly.fills')" align="right" width="80" />
      <el-table-column prop="roundTrips" :label="t('monthly.trips')" align="right" width="80" />
      <el-table-column :label="t('monthly.winRate')" align="right" width="80"><template #default="{ row }">{{ row.winRate == null ? '—' : row.winRate + '%' }}</template></el-table-column>
      <el-table-column :label="t('monthly.turnover')" align="right"><template #default="{ row }">{{ fx(row.turnover, 0) }}</template></el-table-column>
      <el-table-column :label="t('monthly.realized')" align="right"><template #default="{ row }"><span :style="pc(row.realizedPnl)">{{ fx(row.realizedPnl) }}</span></template></el-table-column>
      <el-table-column :label="t('monthly.fees')" align="right"><template #default="{ row }">−{{ fx(row.fees) }}</template></el-table-column>
      <el-table-column :label="t('monthly.net')" align="right"><template #default="{ row }"><b :style="pc(row.netPnl)">{{ fx(row.netPnl) }}</b></template></el-table-column>
    </el-table>
    <div class="hint" style="margin-top:8px">{{ t('monthly.note') }}</div>
  </el-card>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { orderApi } from '@/api'

const { t } = useI18n()
const months = ref(12), loading = ref(false), downloading = ref(false), data = ref({ rows: [], totals: [] }), expanded = ref([])
const totals = computed(() => data.value.totals || [])
const byMonth = computed(() => (data.value.rows || []).reduce((m, r) => { (m[r.month] ||= []).push(r); return m }, {}))
const pc = v => ({ color: v > 0 ? '#26a69a' : v < 0 ? '#ef5350' : '' })
const fx = (v, d = 2) => v == null ? '—' : Number(v).toLocaleString(undefined, { minimumFractionDigits: d, maximumFractionDigits: d })

async function load() {
  loading.value = true
  try { data.value = (await orderApi.monthly(months.value)).data || { rows: [], totals: [] } }
  catch (e) { ElMessage.error(e.response?.data?.message || e.message) }
  finally { loading.value = false }
}
async function download() {
  downloading.value = true
  try {
    const blob = await orderApi.monthlyCsv(months.value)
    const url = URL.createObjectURL(blob instanceof Blob ? blob : new Blob([blob], { type: 'text/csv' }))
    const a = document.createElement('a'); a.href = url; a.download = `pnl-monthly-${new Date().toISOString().slice(0, 7)}.csv`; a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) { ElMessage.error(e.message) }
  finally { downloading.value = false }
}
onMounted(load)
</script>
