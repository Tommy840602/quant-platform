package com.quant.backtest.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BacktestEngineMetricsTest {

    private static List<Double> steady(int n, double perBar) {
        List<Double> eq = new ArrayList<>(); double e = 10000;
        for (int i = 0; i < n; i++) { eq.add(e); e *= 1 + perBar; }
        return eq;
    }

    @Test
    void sharpeAnnualizesByBarInterval() {
        BacktestEngine eng = new BacktestEngine(10000, 0, 0);
        // 同一條權益曲線：1m 的每根報酬 → 一年有 525600 根；1d → 365 根。Sharpe 差 √1440
        List<Double> eq = new ArrayList<>(); double e = 10000;
        double[] r = {0.001, -0.0005, 0.002, -0.001, 0.0015, 0.0, 0.001, -0.0002};
        eq.add(e); for (double x : r) { e *= 1 + x; eq.add(e); }
        double s1m = (double) eng.metrics(eq, 60_000).get("sharpe");
        double s1d = (double) eng.metrics(eq, 86_400_000).get("sharpe");
        assertEquals(Math.sqrt(1440), s1m / s1d, 0.01);
        assertEquals(525_600L, eng.metrics(eq, 60_000).get("bars_per_year"));
    }

    @Test
    void shortSampleIsNotAnnualized() {
        BacktestEngine eng = new BacktestEngine(10000, 0, 0);
        Map<String, Object> m = eng.metrics(steady(300, 0.0001), 60_000);   // 300 分鐘
        assertNull(m.get("annual_return"), "不到一天不年化，否則 300 根 1m 的 3% 會變天文數字");
        assertNotNull(m.get("total_return"));
        assertTrue((double) m.get("total_return") > 0.029);
    }

    @Test
    void dailyBarsAnnualizeAsBefore() {
        BacktestEngine eng = new BacktestEngine(10000, 0, 0);
        Map<String, Object> m = eng.metrics(steady(366, 0.0), 86_400_000);   // 一年持平
        assertEquals(0.0, (double) m.get("annual_return"), 1e-9);
        assertEquals(365.0, (double) m.get("span_days"), 0.01);
    }

    @Test
    void maxDrawdownIsNegativeFraction() {
        BacktestEngine eng = new BacktestEngine(10000, 0, 0);
        Map<String, Object> m = eng.metrics(List.of(100.0, 120.0, 90.0, 100.0), 86_400_000);
        assertEquals(-0.25, (double) m.get("max_drawdown"), 1e-9);
    }
}
