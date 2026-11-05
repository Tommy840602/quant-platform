package com.quant.strategy.service;

import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderStatus;
import com.quant.common.event.OrderEvent;
import com.quant.strategy.core.StrategyContextImpl;
import com.quant.strategy.position.StrategyPositionBook;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DrainingTest {

    @Test
    void exposureStaysUntilPositionAndPendingOrdersAreGone() {
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        StrategyContextImpl c = new StrategyContextImpl("b", mock(OrderClient.class), Map.of("X", 100.0), () -> 10, book);
        assertFalse(StrategyRunner.hasLiveExposure(c));
        book.onSubmitted("o1", "b", "X");
        assertTrue(StrategyRunner.hasLiveExposure(c), "在途單也算（可能正要成交）");
        book.onEvent(new OrderEvent("o1", "x", "X", OrderSide.BUY, OrderStatus.FILLED, 0.1, 100, 0, "main", "e1"));
        assertTrue(StrategyRunner.hasLiveExposure(c), "有真實持倉");
        book.onSubmitted("o2", "b", "X");
        book.onEvent(new OrderEvent("o2", "x", "X", OrderSide.SELL, OrderStatus.PARTIALLY_FILLED, 0.05, 100, 0, "main", "e2"));
        assertTrue(StrategyRunner.hasLiveExposure(c), "只平掉一半：不能切 PAPER");
        book.onEvent(new OrderEvent("o2", "x", "X", OrderSide.SELL, OrderStatus.FILLED, 0.05, 100, 0, "main", "e3"));
        assertFalse(StrategyRunner.hasLiveExposure(c), "全部出清、沒有在途 → 可以切");
    }
}
