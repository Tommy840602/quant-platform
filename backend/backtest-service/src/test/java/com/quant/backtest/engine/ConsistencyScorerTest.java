package com.quant.backtest.engine;

import com.quant.common.enums.OrderSide;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConsistencyScorerTest {

    static final long M = 60_000;
    private static BacktestContext.Trade bt(long t, OrderSide s, double px) { return new BacktestContext.Trade(t, "BTCUSDT", s, 0.01, px); }
    private static ConsistencyScorer.LiveFill lv(long t, OrderSide s, double px) { return new ConsistencyScorer.LiveFill(t, s, 0.01, px); }

    @Test
    void perfectMatchScoresOne() {
        var live = List.of(lv(10 * M, OrderSide.BUY, 100.2), lv(20 * M, OrderSide.SELL, 101));
        var bt = List.of(bt(10 * M, OrderSide.BUY, 100), bt(21 * M, OrderSide.SELL, 101));
        Map<String, Object> r = ConsistencyScorer.score(live, bt, M, 2);
        assertEquals(1.0, (double) r.get("consistencyScore"), 1e-9);
        assertEquals(2, r.get("matched"));
        assertEquals(0.1, (double) r.get("avgAbsPriceDiffPct"), 1e-6);   // (0.2% + 0%) / 2
    }

    @Test
    void wrongSideOrOutsideToleranceDoesNotMatch() {
        var live = List.of(lv(10 * M, OrderSide.BUY, 100), lv(50 * M, OrderSide.SELL, 101));
        var bt = List.of(bt(10 * M, OrderSide.SELL, 100), bt(40 * M, OrderSide.SELL, 101));   // 方向反 / 差 10 根
        Map<String, Object> r = ConsistencyScorer.score(live, bt, M, 2);
        assertEquals(0.0, (double) r.get("consistencyScore"), 1e-9);
        assertEquals(2, r.get("unmatchedLive"));
        assertEquals(2, r.get("unmatchedBacktest"));
        assertNull(r.get("avgAbsPriceDiffPct"));
    }

    @Test
    void eachBacktestTradeUsedOnce() {
        var live = List.of(lv(10 * M, OrderSide.BUY, 100), lv(11 * M, OrderSide.BUY, 100));
        var bt = List.of(bt(10 * M, OrderSide.BUY, 100));
        Map<String, Object> r = ConsistencyScorer.score(live, bt, M, 2);
        assertEquals(1, r.get("matched"));
        assertEquals(0.5, (double) r.get("consistencyScore"), 1e-9);   // 1 / max(2,1)
    }

    @Test
    void extraBacktestTradesLowerTheScore() {
        var live = List.of(lv(10 * M, OrderSide.BUY, 100));
        var bt = List.of(bt(10 * M, OrderSide.BUY, 100), bt(30 * M, OrderSide.SELL, 100), bt(50 * M, OrderSide.BUY, 100), bt(70 * M, OrderSide.SELL, 100));
        Map<String, Object> r = ConsistencyScorer.score(live, bt, M, 2);
        assertEquals(0.25, (double) r.get("consistencyScore"), 1e-9);
    }

    @Test
    void noTradesAnywhereIsNullNotOne() {
        Map<String, Object> r = ConsistencyScorer.score(List.of(), List.of(), M, 2);
        assertNull(r.get("consistencyScore"));
    }
}
