package com.quant.strategy.position;

import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderStatus;
import com.quant.common.event.OrderEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StrategyPositionBookTest {

    private static OrderEvent ev(String cid, OrderSide side, OrderStatus st, double qty) {
        return new OrderEvent(cid, null, "BTCUSDT", side, st, qty, 100, 1L, "main", cid + "|" + st + "|" + qty);
    }

    @Test
    void submittedOrderIsPendingUntilTerminalEvent_andOnlyFillsMovePosition() {
        StrategyPositionBook b = new StrategyPositionBook();
        b.reconcile(List.of());
        b.onSubmitted("c1", "ma-fast", "BTCUSDT");
        assertTrue(b.isPending("ma-fast", "BTCUSDT"));
        assertEquals(0, b.position("ma-fast", "BTCUSDT"));

        assertTrue(b.onEvent(ev("c1", OrderSide.BUY, OrderStatus.FILLED, 0.5)));
        assertEquals(0.5, b.position("ma-fast", "BTCUSDT"), 1e-12);
        assertFalse(b.isPending("ma-fast", "BTCUSDT"));
    }

    @Test
    void rejectedOrderLeavesPositionUntouched() {
        StrategyPositionBook b = new StrategyPositionBook();
        b.reconcile(List.of());
        b.onSubmitted("c2", "rsi", "BTCUSDT");
        b.onEvent(ev("c2", OrderSide.BUY, OrderStatus.REJECTED, 0));
        assertEquals(0, b.position("rsi", "BTCUSDT"));
        assertFalse(b.isPending("rsi", "BTCUSDT"), "拒單後解除 pending");
    }

    @Test
    void sellReducesAndUnknownKeepsPending() {
        StrategyPositionBook b = new StrategyPositionBook();
        b.reconcile(List.of(Map.of("strategyId", "g", "symbol", "BTCUSDT", "qty", 1.0, "openOrders", 0)));
        assertEquals(1.0, b.position("g", "BTCUSDT"), 1e-12);
        b.onSubmitted("c3", "g", "BTCUSDT");
        b.onEvent(ev("c3", OrderSide.SELL, OrderStatus.UNKNOWN, 0));
        assertTrue(b.isPending("g", "BTCUSDT"), "UNKNOWN 不解除 pending");
        b.onEvent(ev("c3", OrderSide.SELL, OrderStatus.FILLED, 1.0));
        assertEquals(0, b.position("g", "BTCUSDT"), 1e-12);
        assertFalse(b.isPending("g", "BTCUSDT"));
    }

    @Test
    void unknownClientOrderIdIsReported() {
        StrategyPositionBook b = new StrategyPositionBook();
        assertFalse(b.onEvent(ev("nope", OrderSide.BUY, OrderStatus.FILLED, 1)));
    }

    @Test
    void reconcileSeedsOpenOrdersAsPending() {
        StrategyPositionBook b = new StrategyPositionBook();
        assertFalse(b.isReconciled());
        b.reconcile(List.of(Map.of("strategyId", "x", "symbol", "ETHUSDT", "qty", 0.0, "openOrders", 2)));
        assertTrue(b.isReconciled());
        assertTrue(b.isPending("x", "ETHUSDT"));
    }
}
