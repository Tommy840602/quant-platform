package com.quant.common.event;

import com.quant.common.enums.OrderStatus;

/** 訂單狀態變更事件（Kafka 主題 order.events）。account：多帳戶。 */
public record OrderEvent(
        String clientOrderId,
        String exchangeOrderId,
        String symbol,
        OrderStatus status,
        double filledQty,
        double avgPrice,
        long timestamp,
        String account
) {}
