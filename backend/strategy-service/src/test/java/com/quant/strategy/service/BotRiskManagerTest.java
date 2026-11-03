package com.quant.strategy.service;

import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderStatus;
import com.quant.common.enums.OrderType;
import com.quant.common.event.OrderEvent;
import com.quant.strategy.core.StrategyContextImpl;
import com.quant.strategy.position.StrategyPositionBook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BotRiskManagerTest {

    static Kline bar(String s, double h, double l, double c, boolean closed) {
        return new Kline(s, "1m", 0, c, h, l, c, 1, 0, closed);
    }
    static OrderIntent buy(String sym) {
        return new OrderIntent(UUID.randomUUID().toString(), "b", sym, OrderSide.BUY, OrderType.MARKET, null, 1, false, "SPOT", "main");
    }
    static OrderEvent fill(String cid, String sym, OrderSide side, double q, double px) {
        return new OrderEvent(cid, "x", sym, side, OrderStatus.FILLED, q, px, 0, "main", cid + "|f");
    }

    /** ATR ≈ 1：每根 high-low = 1，收盤不動。 */
    static void warm(BotRiskManager m, String sym, double px, int n) {
        for (int i = 0; i < n; i++) m.onKline(bar(sym, px + 0.5, px - 0.5, px, true));
    }

    @Test
    void stopLossAndTrailingAndTakeProfit() {
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        Map<String, Object> params = new HashMap<>(Map.of("stopAtr", 2.0, "trailAtr", 3.0, "takeProfitPct", 10.0));
        BotRiskManager m = new BotRiskManager(book, mock(NotifyClient.class));
        m.bind(id -> params, id -> id, null);
        warm(m, "X", 100, 20);
        assertEquals(1.0, m.atr("X"), 1e-9);
        assertNull(m.exitReason("b", "X", 99, 100, 100));        // 跌 1 < 2×ATR
        assertEquals("停損", m.exitReason("b", "X", 97.9, 100, 100));
        assertNull(m.exitReason("b", "X", 104, 100, 106));        // 最高 106，trail = 103
        assertEquals("移動停利", m.exitReason("b", "X", 102.9, 100, 106));
        assertNull(m.exitReason("b", "X", 101, 100, 103), "trail 103-3=100 不高於進場價，不鎖利");
        assertEquals("停利", m.exitReason("b", "X", 110.1, 100, 110.1));
    }

    @Test
    void topKOnlyBestRankedSignalsAreSent() throws Exception {
        OrderClient oms = mock(OrderClient.class);
        when(oms.submit(any())).thenReturn(ApiResponse.ok("{}"));
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        Map<String, Double> px = new ConcurrentHashMap<>();
        BotRiskManager m = new BotRiskManager(book, mock(NotifyClient.class));
        m.bind(id -> Map.of("maxPositions", 2.0), id -> id, null);
        StrategyContextImpl ctx = new StrategyContextImpl("b", oms, px, () -> 0, book);
        ctx.setBuyRouter(m);
        // A 漲最多、C 次之、B 平
        for (String s : List.of("A", "B", "C")) warm(m, s, 100, 15);
        for (int i = 1; i <= 10; i++) { m.onKline(bar("A", 100 + i * 2 + .5, 100 + i * 2 - .5, 100 + i * 2, true));
                                        m.onKline(bar("B", 100.5, 99.5, 100, true));
                                        m.onKline(bar("C", 100 + i + .5, 100 + i - .5, 100 + i, true)); }
        for (String s : List.of("B", "C", "A")) { px.put(s, 100.0); ctx.submit(buy(s)); }
        verify(oms, never()).submit(any());           // 先排名，不馬上送
        m.flush("b");
        ArgumentCaptor<OrderIntent> cap = ArgumentCaptor.forClass(OrderIntent.class);
        verify(oms, times(2)).submit(cap.capture());
        assertEquals(Set.of("A", "C"), new HashSet<>(cap.getAllValues().stream().map(OrderIntent::symbol).toList()));
    }

    @Test
    void consecutiveLossesPauseEntriesButExitsStillWork() {
        OrderClient oms = mock(OrderClient.class);
        when(oms.submit(any())).thenReturn(ApiResponse.ok("{}"));
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        BotRiskManager m = new BotRiskManager(book, mock(NotifyClient.class));
        m.bind(id -> Map.of("maxConsecLosses", 2.0, "maxLossUsdt", 0.0), id -> id, null);
        StrategyContextImpl ctx = new StrategyContextImpl("b", oms, Map.of("X", 100.0), () -> 0, book);
        ctx.setBuyRouter(m);
        m.checkExits("b", bar("X", 100, 100, 100, false), ctx);   // 讓 manager 認得這個 bot
        for (int i = 0; i < 2; i++) {    // 兩次 100 買、90 賣
            book.onSubmitted("b" + i, "b", "X"); book.onEvent(fill("b" + i, "X", OrderSide.BUY, 1, 100));
            book.onSubmitted("s" + i, "b", "X"); book.onEvent(fill("s" + i, "X", OrderSide.SELL, 1, 90));
        }
        assertEquals(2, m.consecLosses("b"));
        assertNotNull(m.pausedReason("b"));
        assertTrue(m.route(ctx, buy("X")), "暫停中：BUY 被攔下");
        assertEquals(0, book.openSlots("b"));
        m.reset("b");
        assertNull(m.pausedReason("b"));
    }

    @Test
    void sellExitsWholePositionRegardlessOfNotional() {
        OrderClient oms = mock(OrderClient.class);
        when(oms.submit(any())).thenReturn(ApiResponse.ok("{}"));
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        book.onSubmitted("b1", "b", "X"); book.onEvent(fill("b1", "X", OrderSide.BUY, 0.37, 10));
        assertEquals(10, book.avgCost("b", "X"), 1e-9);
        StrategyContextImpl ctx = new StrategyContextImpl("b", oms, Map.of("X", 10.0), () -> 6, book);
        ctx.submit(new OrderIntent("s1", "b", "X", OrderSide.SELL, OrderType.MARKET, null, 0.001, false, "SPOT", "main"));
        ArgumentCaptor<OrderIntent> cap = ArgumentCaptor.forClass(OrderIntent.class);
        verify(oms).submit(cap.capture());
        assertEquals(0.37, cap.getValue().quantity(), 1e-12);
    }
}
