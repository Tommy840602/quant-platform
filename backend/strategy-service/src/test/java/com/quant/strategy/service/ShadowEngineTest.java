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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShadowEngineTest {

    static OrderIntent intent(String cid, String sym, OrderSide side, double q) {
        return new OrderIntent(cid, "b", sym, side, OrderType.MARKET, null, q, false, "SPOT", "main");
    }

    @Test
    void paperBotNeverReachesOmsAndRecordsRoundTrip() {
        OrderClient oms = mock(OrderClient.class);
        StrategyPositionBook book = new StrategyPositionBook();   // 不需對帳：模擬盤不看 OMS
        BotRiskManager risk = new BotRiskManager(book, mock(NotifyClient.class));
        risk.bind(id -> Map.of(), id -> id, id -> "ma", null);
        ShadowEngine sh = new ShadowEngine();
        sh.bind(risk, id -> "PAPER", id -> id, null);
        Map<String, Double> px = new ConcurrentHashMap<>(Map.of("X", 10.0));
        StrategyContextImpl ctx = new StrategyContextImpl("b", oms, px, () -> 6, book);
        ctx.setExecution(sh);

        ctx.submit(intent("c1", "X", OrderSide.BUY, 1));
        assertEquals(0.6, ctx.position("X"), 1e-9, "6 USDT / 10");
        assertEquals(10, ctx.entry("X"), 1e-9);
        px.put("X", 9.0);
        ctx.submit(intent("c2", "X", OrderSide.SELL, 1));
        assertEquals(0, ctx.position("X"), 1e-12);
        verify(oms, never()).submit(any());
        assertEquals(1, risk.consecLosses("b"), "模擬盤虧損也算進連虧");
        assertTrue(ctx.lastOutcome().contains("模擬成交"));
    }

    @Test
    void shadowMirrorsLiveAndMeasuresSlippage() {
        OrderClient oms = mock(OrderClient.class);
        when(oms.submit(any())).thenReturn(ApiResponse.ok("{}"));
        StrategyPositionBook book = new StrategyPositionBook(); book.reconcile(List.of());
        BotRiskManager risk = new BotRiskManager(book, mock(NotifyClient.class));
        risk.bind(id -> Map.of(), id -> id, id -> "ma", null);
        ShadowEngine sh = new ShadowEngine();
        sh.bind(risk, id -> "LIVE", id -> id, null);
        sh.attach(book);
        Map<String, Double> px = new ConcurrentHashMap<>(Map.of("X", 100.0));
        StrategyContextImpl ctx = new StrategyContextImpl("b", oms, px, () -> 10, book);
        ctx.setExecution(sh);

        ctx.submit(intent("c1", "X", OrderSide.BUY, 1));                       // 參考價 100
        book.onEvent(new OrderEvent("c1", "x", "X", OrderSide.BUY, OrderStatus.FILLED, 0.1, 100.2, 0, "main", "e1"));   // 成交 100.2
        assertEquals(20, sh.avgSlipBps("b"), 1e-6, "買貴 0.2% = 20 bps");
        sh.onTick("b", new Kline("X", "1m", 0, 101, 101, 101, 101, 1, 0, false));
        assertEquals(0.1, sh.shadowPnl("b"), 1e-9, "影子 0.1 顆從 100 漲到 101");
    }

    @Test
    void volatilityTargetedSizing() {
        StrategyPositionBook book = new StrategyPositionBook();
        BotRiskManager risk = new BotRiskManager(book, mock(NotifyClient.class));
        risk.bind(id -> Map.of("riskPerTradePct", 0.5, "stopAtr", 2.0, "sizeCapPct", 100.0), id -> id, id -> "ma", null);
        risk.setEquity(() -> 1000);
        for (int i = 0; i < 20; i++) risk.onKline(new Kline("X", "1m", 0, 100, 100.5, 99.5, 100, 1, 0, true));   // ATR = 1
        // 風險預算 1000 × 0.5% = 5 USDT；停損距離 2 × ATR = 2 → 2.5 顆
        assertEquals(2.5, risk.sizeQty("b", "X", 100), 1e-9);
        risk.bind(id -> Map.of("riskPerTradePct", 0.5, "stopAtr", 2.0, "sizeCapPct", 10.0), id -> id, id -> "ma", null);
        assertEquals(1.0, risk.sizeQty("b", "X", 100), 1e-9, "上限 1000 × 10% = 100 USDT = 1 顆");
        risk.bind(id -> Map.of(), id -> id, id -> "ma", null);
        assertEquals(0, risk.sizeQty("b", "X", 100), "預設關閉");
    }

    @Test
    void pilotStageUsesMinimumOrder() {
        ShadowEngine sh = new ShadowEngine();
        sh.bind(null, id -> "PILOT", id -> id, null);
        assertEquals(0.055, sh.sizeQty("b", "X", 100), 1e-12, "5.5 USDT / 100");
    }

    @Test
    void tripStatsProfitFactor() {
        var t = RoundTripRepository.TripStats.of(List.of(2.0, -1.0, 1.0, -0.5), List.of(0.02, -0.01, 0.01, -0.005));
        assertEquals(4, t.n()); assertEquals(2, t.wins());
        assertEquals(2.0, t.profitFactor(), 1e-9);
        assertEquals(0.00375, t.meanRet(), 1e-12);
        assertEquals(99, RoundTripRepository.TripStats.of(List.of(1.0), List.of(0.01)).profitFactor());
    }
}
