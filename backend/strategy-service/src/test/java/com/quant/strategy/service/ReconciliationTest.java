package com.quant.strategy.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReconciliationTest {

    @Test
    @SuppressWarnings("unchecked")
    void decomposesLiveVsShadowGapIntoFeesSlippageAndOther() {
        var trips = List.of(
                new Reconciliation.TripRow("SHADOW", "BTCUSDT", 10, 6, 20.0, 0, 0.004),
                new Reconciliation.TripRow("LIVE", "BTCUSDT", 9, 5, 8.0, 5000, 0.0015),     // 成交額 5000 → 手續費 5
                new Reconciliation.TripRow("PAPER", "BTCUSDT", 20, 12, 30.0, 8000, 0.003),
                new Reconciliation.TripRow("LIVE", "DOGEUSDT", 3, 0, -2.0, 1000, -0.002));
        var slips = List.of(new Reconciliation.SlipRow("BTCUSDT", 18, 4.0, 3.0, 18),
                            new Reconciliation.SlipRow("DOGEUSDT", 6, 20.0, 1.5, 6));
        Map<String, Object> r = Reconciliation.build(trips, slips, 0.001, 2);
        Map<String, Object> ex = (Map<String, Object>) r.get("execution");
        assertEquals(6.0, ex.get("livePnl"));
        assertEquals(20.0, ex.get("shadowPnl"));
        assertEquals(-14.0, ex.get("gap"));
        assertEquals(-6.0, ex.get("fees"), "(5000+1000)×0.1%");
        assertEquals(-4.5, ex.get("slippage"));
        assertEquals(-3.5, ex.get("other"), "−14 − (−6) − (−4.5)");
        assertEquals(0, ex.get("missedTrips"), "實盤 12 筆 ≥ 影子 10 筆");
        var fewer = Reconciliation.build(List.of(new Reconciliation.TripRow("SHADOW", "X", 5, 3, 1, 0, 0),
                new Reconciliation.TripRow("LIVE", "X", 3, 2, 1, 0, 0)), List.of(), 0.001, 0);
        assertEquals(2, ((Map<String, Object>) fewer.get("execution")).get("missedTrips"), "影子有、實盤沒成的 2 筆");
    }

    @Test
    @SuppressWarnings("unchecked")
    void promotionDeltaAndSymbolOrdering() {
        var trips = List.of(
                new Reconciliation.TripRow("PAPER", "A", 10, 6, 10, 0, 0.005),
                new Reconciliation.TripRow("LIVE", "A", 6, 3, 1, 0, 0.001),
                new Reconciliation.TripRow("SHADOW", "A", 6, 4, 5, 0, 0.0),
                new Reconciliation.TripRow("LIVE", "B", 2, 2, 3, 0, 0.0),
                new Reconciliation.TripRow("SHADOW", "B", 2, 2, 3, 0, 0.0));
        Map<String, Object> r = Reconciliation.build(trips, List.of(), 0.001, 0);
        Map<String, Object> p = (Map<String, Object>) r.get("promotion");
        assertEquals(0.5, p.get("paperMeanRetPct"));
        assertEquals(0.075, p.get("liveMeanRetPct"), "(0.001×6 + 0×2)/8");
        assertEquals(true, p.get("enough"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("symbols");
        assertEquals("A", rows.get(0).get("symbol"), "差距最大的排前面");
        assertEquals(-4.0, rows.get(0).get("gap"));
    }

    @Test
    void emptyIsSafe() {
        Map<String, Object> r = Reconciliation.build(List.of(), List.of(), 0.001, 0);
        assertNotNull(r.get("execution"));
        assertEquals(false, ((Map<?, ?>) r.get("promotion")).get("enough"));
    }
}
