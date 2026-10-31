package com.quant.strategy.core;

import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;
import com.quant.strategy.position.StrategyPositionBook;
import com.quant.strategy.service.OrderClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StrategyContextImplTest {

    private static OrderIntent buy(String cid) {
        return new OrderIntent(cid, "s", "BTCUSDT", OrderSide.BUY, OrderType.MARKET, null, 0.01, false, "SPOT", "main");
    }

    @Test
    void rejectionDoesNotCreateAPosition() {
        OrderClient oms = mock(OrderClient.class);
        when(oms.submit(any())).thenReturn(ApiResponse.error(403, "risk rejected"));
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        StrategyContextImpl ctx = new StrategyContextImpl("s", oms, Map.of("BTCUSDT", 100.0), () -> 0, book);

        ctx.submit(buy("c1"));
        assertEquals(0, ctx.position("BTCUSDT"), "以前這裡會變成 0.01");
        assertFalse(book.isPending("s", "BTCUSDT"));
    }

    @Test
    void acceptedOrderIsPendingNotPosition_andSecondSignalIsSuppressed() {
        OrderClient oms = mock(OrderClient.class);
        when(oms.submit(any())).thenReturn(ApiResponse.ok("{}"));
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        StrategyContextImpl ctx = new StrategyContextImpl("s", oms, Map.of("BTCUSDT", 100.0), () -> 0, book);

        ctx.submit(buy("c1"));
        assertEquals(0, ctx.position("BTCUSDT"), "未成交前部位為 0");
        assertTrue(book.isPending("s", "BTCUSDT"));
        ctx.submit(buy("c2"));
        verify(oms, times(1)).submit(any());   // 第二次被 pending 擋下
    }

    @Test
    void noTradingBeforeReconcile() {
        OrderClient oms = mock(OrderClient.class);
        StrategyPositionBook book = new StrategyPositionBook();   // not reconciled
        new StrategyContextImpl("s", oms, Map.of(), () -> 0, book).submit(buy("c1"));
        verify(oms, never()).submit(any());
    }

    @Test
    void unknown202KeepsPending() {
        OrderClient oms = mock(OrderClient.class);
        when(oms.submit(any())).thenReturn(ApiResponse.error(202, "UNKNOWN"));
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        new StrategyContextImpl("s", oms, Map.of(), () -> 0, book).submit(buy("c1"));
        assertTrue(book.isPending("s", "BTCUSDT"));
    }

    @Test
    void botOrdersFollowActiveAccountNotTheHardcodedMain() {
        OrderClient oms = mock(OrderClient.class);
        when(oms.submit(any())).thenReturn(ApiResponse.ok("{}"));
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        String[] mode = {"paper"};
        StrategyContextImpl ctx = new StrategyContextImpl("s", oms, Map.of("BTCUSDT", 100.0), () -> 0, book, () -> mode[0]);

        ctx.submit(buy("c1"));                       // 策略類別裡寫的是 "main"
        org.mockito.ArgumentCaptor<OrderIntent> cap = org.mockito.ArgumentCaptor.forClass(OrderIntent.class);
        verify(oms).submit(cap.capture());
        assertEquals("paper", cap.getValue().account(), "跟著平台切換，不是策略寫死的 main");

        book.reconcile(List.of()); mode[0] = "main";
        StrategyContextImpl ctx2 = new StrategyContextImpl("s2", oms, Map.of("BTCUSDT", 100.0), () -> 0, book, () -> mode[0]);
        ctx2.submit(buy("c2"));
        verify(oms, times(2)).submit(cap.capture());
        assertEquals("main", cap.getValue().account());
    }

    @Test
    void defaultConstructorIsPaper() {
        OrderClient oms = mock(OrderClient.class);
        when(oms.submit(any())).thenReturn(ApiResponse.ok("{}"));
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        new StrategyContextImpl("s", oms, Map.of("BTCUSDT", 100.0), () -> 0, book).submit(buy("c1"));
        org.mockito.ArgumentCaptor<OrderIntent> cap = org.mockito.ArgumentCaptor.forClass(OrderIntent.class);
        verify(oms).submit(cap.capture());
        assertEquals("paper", cap.getValue().account(), "沒指定就是虛擬，安全");
    }
}
