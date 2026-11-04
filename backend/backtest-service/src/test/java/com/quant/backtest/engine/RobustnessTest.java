package com.quant.backtest.engine;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RobustnessTest {

    static double[] noise(Random r, int T, double drift) {
        double[] x = new double[T]; for (int i = 0; i < T; i++) x[i] = drift + 0.01 * r.nextGaussian(); return x;
    }

    @Test
    void pureNoiseGridIsFlaggedAsOverfit() {
        // 純雜訊網格：不論哪個亂數種子，都不該被判成 ROBUST，且 DSR（扣掉 30 次試驗的運氣）不顯著
        for (int seed : new int[]{1, 2, 3, 7}) {
            Random r = new Random(seed);
            List<double[]> rets = new ArrayList<>();
            for (int n = 0; n < 30; n++) rets.add(noise(r, 1000, 0));
            Map<String, Object> a = Robustness.analyze(rets, 525600);
            double dsr = (double) a.get("dsr");
            assertTrue(dsr < 0.9, "seed " + seed + " DSR=" + dsr);
            assertNotEquals("ROBUST", a.get("verdict"), "seed " + seed);
        }
    }

    @Test
    void genuineEdgeSurvives() {
        Random r = new Random(11);
        List<double[]> rets = new ArrayList<>();
        for (int n = 0; n < 20; n++) rets.add(noise(r, 2000, 0));
        rets.add(noise(r, 2000, 0.0015));   // 真的有 edge：每根 +0.15%，Sharpe/根 ≈ 0.15
        Map<String, Object> a = Robustness.analyze(rets, 525600);
        assertEquals(20, a.get("bestIndex"));
        assertEquals(20, a.get("recommendedIndex"));
        assertTrue((double) a.get("pbo") < 0.1, "PBO=" + a.get("pbo"));
        assertTrue((double) a.get("dsr") > 0.95, "DSR=" + a.get("dsr"));
        assertEquals("ROBUST", a.get("verdict"), String.valueOf(a.get("reasons")));
        assertEquals(252, a.get("splits"));
    }

    @Test
    void normalDistributionHelpers() {
        assertEquals(0.5, Robustness.phi(0), 1e-7);
        assertEquals(0.975, Robustness.phi(1.959964), 1e-6);
        assertEquals(1.959964, Robustness.invPhi(0.975), 1e-5);
        assertEquals(-2.326348, Robustness.invPhi(0.01), 1e-5);
    }

    @Test
    void tooFewBars() {
        assertEquals("INSUFFICIENT", Robustness.analyze(List.of(new double[10]), 1).get("verdict"));
    }
}
