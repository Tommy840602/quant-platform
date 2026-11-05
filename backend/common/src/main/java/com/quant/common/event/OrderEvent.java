package com.quant.common.event;

import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderStatus;

/**
 * 訂單狀態變更事件（Kafka 主題 order.events）。account：多帳戶。
 * side：成交方向，部位必須以有號數量累計（BUY 增、SELL 減）；null 代表舊版事件，消費者應拒絕更新部位。
 * eventId：消費端去重鍵（Kafka at-least-once 會重送）。由發送端生成，格式自由但需穩定不重複。
 * env / market / executionMode：testnet|mainnet|demo、SPOT|USDT_M、LIVE|DRY —— 帳務以這幾個維度分開記，
 * 現貨與合約、模擬與真單不可加到同一個部位。舊事件為 null（消費端回查訂單補齊）。
 */
public record OrderEvent(
        String clientOrderId,
        String exchangeOrderId,
        String symbol,
        OrderSide side,
        OrderStatus status,
        double filledQty,
        double avgPrice,
        long timestamp,
        String account,
        String eventId,
        String env,
        String market,
        String executionMode
) {
    /** 舊呼叫點（不帶 env / market / mode）；OMS 發送前會用訂單資料補齊。 */
    public OrderEvent(String clientOrderId, String exchangeOrderId, String symbol, OrderSide side, OrderStatus status,
                      double filledQty, double avgPrice, long timestamp, String account, String eventId) {
        this(clientOrderId, exchangeOrderId, symbol, side, status, filledQty, avgPrice, timestamp, account, eventId, null, null, null);
    }

    public OrderEvent withDims(String env, String market, String executionMode) {
        return new OrderEvent(clientOrderId, exchangeOrderId, symbol, side, status, filledQty, avgPrice, timestamp, account, eventId,
                env, market, executionMode);
    }
    public boolean futures() { return "USDT_M".equalsIgnoreCase(market); }
    public boolean dry() { return "DRY".equalsIgnoreCase(executionMode); }

    /** 便於舊呼叫點：以 clientOrderId+status+filledQty+timestamp 產生去重鍵。 */
    public static String eventId(String clientOrderId, OrderStatus status, double filledQty, long ts) {
        return clientOrderId + "|" + status + "|" + filledQty + "|" + ts;
    }
    public double signedQty() {
        return side == OrderSide.SELL ? -filledQty : filledQty;
    }
}
