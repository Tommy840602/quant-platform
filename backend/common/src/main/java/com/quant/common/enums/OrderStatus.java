package com.quant.common.enums;

/** 訂單狀態機（見架構文件 §9.1）。 */
public enum OrderStatus {
    CREATED,          // 已建立下單意圖
    RISK_APPROVED,    // 風控放行
    SUBMITTED,        // 已送交易所
    PARTIALLY_FILLED, // 部分成交
    FILLED,           // 完全成交
    CANCELED,         // 已撤銷
    REJECTED,         // 遭拒（風控/交易所）
    EXPIRED           // 逾期
}
