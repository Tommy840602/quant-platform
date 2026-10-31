package com.quant.order.service;

/** 下單模式變動（上膛 / 關 Dry-Run / 切 env）；UDS 聽到後若尚未啟動就啟動。 */
public record TradingModeChangedEvent(boolean effectiveDryRun, String env, String by) {}
