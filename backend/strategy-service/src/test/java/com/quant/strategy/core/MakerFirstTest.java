package com.quant.strategy.core;

import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderStatus;
import com.quant.common.enums.OrderType;
import com.quant.common.event.OrderEvent;
import com.quant.strategy.position.StrategyPositionBook;
import com.quant.strategy.service.OrderClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MakerFirstTest {

    /** 只開限價優先、給固定買一賣一的執行掛鉤。 */
    static StrategyContextImpl.Execution exec(double bid, double ask) {
        return new StrategyContextImpl.Execution() {
            public boolean paper(String id) { return false; }
            public void paperFill(String id, OrderIntent i, double px) {}
            public double paperPosition(String id, String s) { return 0; }
            public double paperEntry(String id, String s) { return 0; }
            public Map<String, Double> paperSnapshot(String id) { return Map.of(); }
            public void onLiveSend(String id, OrderIntent i, double refPx) {}
            public double sizeQty(String id, String s, double px) { return 0; }
            public double param(String id, String k) { return "execMaker".equals(k) ? 1 : "makerWaitSec".equals(k) ? 2 : 0; }
            public double[] quote(String s) { return new double[]{bid, ask}; }
        };
    }

    static OrderIntent buy() { return new OrderIntent("c1", "b", "X", OrderSide.BUY, OrderType.MARKET, null, 1, false, "SPOT", "main"); }

    @Test
    void limitAtBidThenCancelAndMarketTheRemainder() throws Exception {
        OrderClient oms = mock(OrderClient.class);
        when(oms.submit(any())).thenReturn(ApiResponse.ok("{}"));
        when(oms.cancel(anyString(), anyString(), anyString())).thenReturn(ApiResponse.ok("{}"));
        // 交易所：撤單完成時累計成交 0.04（本地成交回報即使還沒到，也以這個數字為準）
        when(oms.query(anyString(), anyString(), anyString())).thenReturn(ApiResponse.ok("{\"status\":\"CANCELED\",\"executedQty\":\"0.04\"}"));
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        StrategyContextImpl ctx = new StrategyContextImpl("b", oms, Map.of("X", 100.0), () -> 10, book);
        ctx.setExecution(exec(99.9, 100.1));

        ctx.submit(buy());
        ArgumentCaptor<OrderIntent> cap = ArgumentCaptor.forClass(OrderIntent.class);
        verify(oms).submit(cap.capture());
        OrderIntent lim = cap.getValue();
        assertEquals(OrderType.LIMIT, lim.type());
        assertEquals(99.9, lim.price(), 1e-12, "買單掛買一價");
        assertEquals(0.1, lim.quantity(), 1e-12, "10 USDT / 100");

        // 掛著的時候成交一半
        book.onEvent(new OrderEvent("c1", "x", "X", OrderSide.BUY, OrderStatus.PARTIALLY_FILLED, 0.04, 99.9, 0, "main", "e1"));
        Thread.sleep(2500);   // makerWaitSec = 2 → 撤單
        verify(oms).cancel(eq("c1"), eq("X"), eq("SPOT"));
        book.onEvent(new OrderEvent("c1", "x", "X", OrderSide.BUY, OrderStatus.CANCELED, 0, 0, 0, "main", "e2"));
        Thread.sleep(1800);   // 撤單後 1.5 秒補市價
        verify(oms, times(2)).submit(cap.capture());
        OrderIntent mkt = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertEquals(OrderType.MARKET, mkt.type());
        assertEquals(0.06, mkt.quantity(), 1e-12, "只補沒成交的 0.06");
        assertTrue(book.isPending("b", "X"), "市價單在途 → 仍 pending");
    }

    @Test
    void lateLocalFillReportDoesNotCauseOverbuy() throws Exception {
        OrderClient oms = mock(OrderClient.class);
        when(oms.submit(any())).thenReturn(ApiResponse.ok("{}"));
        when(oms.cancel(anyString(), anyString(), anyString())).thenReturn(ApiResponse.ok("{}"));
        // 本地完全沒收到成交回報，但交易所說其實成交了 0.09 → 只補 0.01（不足最低名目就不補）
        when(oms.query(anyString(), anyString(), anyString())).thenReturn(ApiResponse.ok("{\"status\":\"CANCELED\",\"executedQty\":\"0.09\"}"));
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        StrategyContextImpl ctx = new StrategyContextImpl("b", oms, Map.of("X", 100.0), () -> 10, book);
        ctx.setExecution(exec(99.9, 100.1));
        ctx.submit(buy());
        Thread.sleep(2800);
        verify(oms, times(1)).submit(any());   // 0.01 × 100 = 1 USDT < 最低名目 → 不補
    }

    @Test
    void unconfirmedCancelNeverRefills() throws Exception {
        OrderClient oms = mock(OrderClient.class);
        when(oms.submit(any())).thenReturn(ApiResponse.ok("{}"));
        when(oms.cancel(anyString(), anyString(), anyString())).thenReturn(ApiResponse.ok("{}"));
        when(oms.query(anyString(), anyString(), anyString())).thenReturn(ApiResponse.ok("{\"status\":\"NEW\"}"));
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        StrategyContextImpl ctx = new StrategyContextImpl("b", oms, Map.of("X", 100.0), () -> 10, book);
        ctx.setExecution(exec(99.9, 100.1));
        ctx.submit(buy());
        Thread.sleep(4000);
        verify(oms, times(1)).submit(any());   // 確認不到終態：不補單
    }

    @Test
    void timeoutKeepsOrderPendingSoNoDuplicate() {
        OrderClient oms = mock(OrderClient.class);
        when(oms.submit(any())).thenThrow(new RuntimeException("Read timed out"));
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        StrategyContextImpl ctx = new StrategyContextImpl("b", oms, Map.of("X", 100.0), () -> 10, book);
        ctx.submit(buy());
        assertTrue(book.isPending("b", "X"), "逾時 = 狀態不明，維持在途");
        ctx.submit(buy());
        verify(oms, times(1)).submit(any());   // 第二次訊號被「仍有未結單」擋下
    }

    @Test
    void fullyFilledLimitNeedsNoFallback() throws Exception {
        OrderClient oms = mock(OrderClient.class);
        when(oms.submit(any())).thenReturn(ApiResponse.ok("{}"));
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        StrategyContextImpl ctx = new StrategyContextImpl("b", oms, Map.of("X", 100.0), () -> 10, book);
        ctx.setExecution(exec(99.9, 100.1));
        ctx.submit(buy());
        book.onEvent(new OrderEvent("c1", "x", "X", OrderSide.BUY, OrderStatus.FILLED, 0.1, 99.9, 0, "main", "e1"));
        Thread.sleep(2500);
        verify(oms, never()).cancel(anyString(), anyString(), anyString());
        verify(oms, times(1)).submit(any());
        assertEquals(0.1, ctx.position("X"), 1e-12);
    }

    @Test
    void riskExitsAlwaysUseMarket() {
        OrderClient oms = mock(OrderClient.class);
        when(oms.submit(any())).thenReturn(ApiResponse.ok("{}"));
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        book.onSubmitted("b0", "b", "X");
        book.onEvent(new OrderEvent("b0", "x", "X", OrderSide.BUY, OrderStatus.FILLED, 0.1, 100, 0, "main", "e0"));
        StrategyContextImpl ctx = new StrategyContextImpl("b", oms, Map.of("X", 95.0), () -> 10, book);
        ctx.setExecution(exec(94.9, 95.1));
        ctx.exit("X", "停損");
        ArgumentCaptor<OrderIntent> cap = ArgumentCaptor.forClass(OrderIntent.class);
        verify(oms).submit(cap.capture());
        assertEquals(OrderType.MARKET, cap.getValue().type(), "停損不賭限價成交");
    }

    @Test
    void terminalEventKeepsPendingWhileAnotherOrderForSameSymbolIsInFlight() {
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        book.onSubmitted("a", "b", "X");
        book.onSubmitted("m", "b", "X");
        book.onEvent(new OrderEvent("a", "x", "X", OrderSide.BUY, OrderStatus.CANCELED, 0, 0, 0, "main", "e"));
        assertTrue(book.isPending("b", "X"));
        book.onEvent(new OrderEvent("m", "x", "X", OrderSide.BUY, OrderStatus.FILLED, 1, 1, 0, "main", "f"));
        assertFalse(book.isPending("b", "X"));
    }
}
