package com.quant.common.dto;

import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;

/**
 * 下單意圖（見架構文件 §9.3）。market：SPOT|USDT_M；account：多帳戶識別（null 視為 main）。
 */
public record OrderIntent(
        String clientOrderId,
        String strategyId,
        String symbol,
        OrderSide side,
        OrderType type,
        Double price,
        double quantity,
        boolean reduceOnly,
        String market,
        String account
) {
    public boolean isFutures() { return "USDT_M".equalsIgnoreCase(market); }
    public String accountOrDefault() { return account == null || account.isBlank() ? "main" : account; }
}
