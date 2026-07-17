package com.quant.common.strategy;

import com.quant.common.dto.OrderIntent;

/** 策略執行上下文：統一下單與部位查詢（回測用模擬器、實盤用 OMS，各自實作）。 */
public interface StrategyContext {
    void submit(OrderIntent intent);
    double position(String symbol);
    double price(String symbol);
}
