package com.quant.order.service;

import com.quant.common.event.OrderEvent;
import com.quant.order.entity.OrderEntity;
import com.quant.order.repository.OrderRepository;

/** 發送訂單事件前補齊 env / market / executionMode（以訂單落地資料為準）。 */
final class OrderEvents {
    private OrderEvents() {}

    static final String DRY = "DRY", LIVE = "LIVE";

    /** 模擬單判斷：新欄位優先；舊資料退回 exchange_order_id 的 DRYRUN 標記。 */
    static boolean isDry(OrderEntity o) {
        if (o == null) return false;
        if (o.getExecutionMode() != null) return DRY.equals(o.getExecutionMode());
        String x = o.getExchangeOrderId();
        return x != null && x.startsWith(OrderService.DRYRUN_MARK);
    }

    static OrderEvent enrich(OrderEvent e, OrderRepository orders) {
        if (e == null || (e.env() != null && e.market() != null && e.executionMode() != null) || orders == null) return e;
        return orders.findByClientOrderId(e.clientOrderId())
                .map(o -> e.withDims(o.getEnv(), o.getMarket() == null ? "SPOT" : o.getMarket(), isDry(o) ? DRY : LIVE))
                .orElse(e);
    }
}
