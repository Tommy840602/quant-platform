package com.quant.common.strategy;

import com.quant.common.dto.Kline;

/**
 * 統一策略介面（見架構文件 §7.4）。回測與實盤共用，策略不感知底層執行環境。
 */
public interface Strategy {
    String id();
    void onBar(Kline bar, StrategyContext ctx);
}
