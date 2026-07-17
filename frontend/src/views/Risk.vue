<template>
  <el-card header="風控設定（見架構文件 §10）">
    <el-form label-width="160px" style="max-width:520px">
      <el-form-item label="單筆最大數量"><el-input-number v-model="cfg.maxOrderQty" :step="0.001" /></el-form-item>
      <el-form-item label="單標的部位上限"><el-input-number v-model="cfg.maxPosition" :step="0.01" /></el-form-item>
      <el-form-item label="最大槓桿"><el-input-number v-model="cfg.maxLeverage" :min="1" :max="125" /></el-form-item>
      <el-form-item label="帳戶最大回撤(%)"><el-input-number v-model="cfg.maxDrawdown" :min="1" :max="100" /></el-form-item>
      <el-form-item label="全域停止交易 (kill switch)"><el-switch v-model="cfg.killSwitch" /></el-form-item>
      <el-form-item>
        <el-button type="primary" @click="save">儲存</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup>
import { reactive } from 'vue'
import { ElMessage } from 'element-plus'
const cfg = reactive({ maxOrderQty: 1.0, maxPosition: 0.5, maxLeverage: 5, maxDrawdown: 20, killSwitch: false })
// TODO: 讀寫 risk-service 設定（Nacos 熱更新）
function save() { ElMessage.success('已儲存（示意，尚未串接後端）') }
</script>
