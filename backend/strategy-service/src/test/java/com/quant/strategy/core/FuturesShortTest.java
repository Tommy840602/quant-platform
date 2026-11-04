package com.quant.strategy.core;

import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderStatus;
import com.quant.common.enums.OrderType;
import com.quant.common.event.OrderEvent;
import com.quant.common.risk.RiskRules;
import com.quant.strategy.position.StrategyPositionBook;
import com.quant.strategy.service.OrderClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FuturesShortTest {

    static OrderEvent fill(String cid, OrderSide side, double q, double px) {
        return new OrderEvent(cid, "x", "X", side, OrderStatus.FILLED, q, px, 0, "main", cid + "|f");
    }

    @Test
    void bookTracksShortCostAndReportsCoverPnl() {
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        List<StrategyPositionBook.Close> closes = new ArrayList<>();
        book.setCloseListener(closes::add);
        book.onSubmitted("s1", "b", "X"); book.onEvent(fill("s1", OrderSide.SELL, 1, 100));
        assertEquals(-1, book.position("b", "X"), 1e-12);
        assertEquals(100, book.avgCost("b", "X"), 1e-12);
        book.onSubmitted("c1", "b", "X"); book.onEvent(fill("c1", OrderSide.BUY, 1, 90));
        assertEquals(0, book.position("b", "X"), 1e-12);
        assertEquals(1, closes.size());
        assertEquals(10 - 190 * 0.001, closes.get(0).pnl(), 1e-9, "空 100 回補 90 賺 10，扣來回手續費");
    }

    @Test
    void futuresContextOpensShortAndCoversWithReduceOnly() {
        OrderClient oms = mock(OrderClient.class);
        when(oms.submit(any())).thenReturn(ApiResponse.ok("{}"));
        when(oms.leverage(anyString(), anyInt())).thenReturn(ApiResponse.ok("{}"));
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        StrategyContextImpl ctx = new StrategyContextImpl("fb", oms, Map.of("X", 100.0), () -> 10, book);
        ctx.setMarket("USDT_M");

        ctx.submit(new OrderIntent("o1", "fb", "X", OrderSide.SELL, OrderType.MARKET, null, 1, false, "SPOT", "main"));
        ArgumentCaptor<OrderIntent> cap = ArgumentCaptor.forClass(OrderIntent.class);
        verify(oms).submit(cap.capture());
        assertEquals("USDT_M", cap.getValue().market());
        assertFalse(cap.getValue().reduceOnly(), "開空不是 reduceOnly");
        assertEquals(0.1, cap.getValue().quantity(), 1e-12);
        verify(oms).leverage("X", 1);

        book.onEvent(fill("o1", OrderSide.SELL, 0.1, 100));
        ctx.submit(new OrderIntent("o2", "fb", "X", OrderSide.BUY, OrderType.MARKET, null, 1, false, "SPOT", "main"));
        verify(oms, times(2)).submit(cap.capture());
        OrderIntent cover = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertEquals(OrderSide.BUY, cover.side());
        assertTrue(cover.reduceOnly(), "回補空單 reduceOnly");
        assertEquals(0.1, cover.quantity(), 1e-12, "回補全部空單");
        verify(oms, times(1)).leverage(anyString(), anyInt());
    }

    @Test
    void spotNeverSellsWhatItDoesNotHold() {
        OrderClient oms = mock(OrderClient.class);
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        StrategyContextImpl ctx = new StrategyContextImpl("sb", oms, Map.of("X", 100.0), () -> 10, book);
        ctx.submit(new OrderIntent("o1", "sb", "X", OrderSide.SELL, OrderType.MARKET, null, 1, false, "SPOT", "main"));
        verify(oms, never()).submit(any());
    }

    @Test
    void shortExitRulesMirrorLongOnes() {
        Map<String, Object> p = Map.of("stopAtr", 2.0, "trailAtr", 3.0, "takeProfitPct", 10.0);
        assertNull(RiskRules.exitReasonShort(101, 100, 100, 1, p));
        assertEquals("停損", RiskRules.exitReasonShort(102.1, 100, 100, 1, p), "空單停損在進場價 + 2 ATR");
        assertNull(RiskRules.exitReasonShort(96, 100, 94, 1, p), "最低 94，移動停利 97");
        assertEquals("移動停利", RiskRules.exitReasonShort(97.1, 100, 94, 1, p));
        assertEquals("停利", RiskRules.exitReasonShort(89.9, 100, 89.9, 1, p));
    }
}
