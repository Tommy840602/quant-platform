package com.quant.common.event;

import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderEventTest {
    @Test
    void sellFillsAreNegative() {
        OrderEvent buy = new OrderEvent("c1", null, "BTCUSDT", OrderSide.BUY, OrderStatus.FILLED, 0.5, 100, 1L, "main", "e1");
        OrderEvent sell = new OrderEvent("c2", null, "BTCUSDT", OrderSide.SELL, OrderStatus.FILLED, 0.5, 100, 2L, "main", "e2");
        assertEquals(0.5, buy.signedQty());
        assertEquals(-0.5, sell.signedQty());
    }

    @Test
    void eventIdIsStableForSameFact() {
        assertEquals(OrderEvent.eventId("c", OrderStatus.FILLED, 1.0, 10L),
                     OrderEvent.eventId("c", OrderStatus.FILLED, 1.0, 10L));
        assertNotEquals(OrderEvent.eventId("c", OrderStatus.FILLED, 1.0, 10L),
                        OrderEvent.eventId("c", OrderStatus.FILLED, 1.0, 11L));
    }
}
