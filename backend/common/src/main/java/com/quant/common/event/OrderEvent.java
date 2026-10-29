package com.quant.common.event;

import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderStatus;

/**
 * 訂單狀態變更事件（Kafka 主題 order.events）。account：多帳戶。
 * side：成交方向，部位必須以有號數量累計（BUY 增、SELL 減）；null 代表舊版事件，消費者應拒絕更新部位。
 * eventId：消費端去重鍵（Kafka at-least-once 會重送）。由發送端生成，格式自由但需穩定不重複。
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
        String eventId
) {
    /** 便於舊呼叫點：以 clientOrderId+status+filledQty+timestamp 產生去重鍵。 */
    public static String eventId(String clientOrderId, OrderStatus status, double filledQty, long ts) {
        return clientOrderId + "|" + status + "|" + filledQty + "|" + ts;
    }
    public double signedQty() {
        return side == OrderSide.SELL ? -filledQty : filledQty;
    }
}
