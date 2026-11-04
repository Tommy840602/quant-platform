package com.quant.backtest.engine;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;
import com.quant.common.strategy.MovingAverageStrategy;
import com.quant.common.strategy.Strategy;
import com.quant.common.strategy.StrategyContext;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 回測套用與實盤相同的機器人風控（common.risk.RiskRules / BarStats）。 */
class RiskParityTest {

    static final Map<String, Object> ALL_OFF = Map.of("stopAtr", 0, "trailAtr", 0, "regimeFilter", 0, "blockHighVol", 0,
            "maxConsecLosses", 0, "maxLossUsdt", 0, "cooldownBars", 0);

    static Kline k(int i, double o, double h, double l, double c) { return new Kline("X", "1m", i * 60000L, o, h, l, c, 1, i * 60000L + 59999, true); }

    static List<Kline> path(long seed) {
        List<Kline> bars = new ArrayList<>(); double c = 100; Random r = new Random(seed);
        for (int i = 0; i < 600; i++) {
            double drift = i < 200 ? 0.0015 : i < 260 ? -0.01 : (i < 450 ? 0.0 : 0.002);
            double o = c; c = c * (1 + drift + 0.002 * r.nextGaussian());
            bars.add(k(i, o, Math.max(o, c) * 1.001, Math.min(o, c) * 0.999, c));
        }
        return bars;
    }

    @Test
    void guardWithEverythingOffMatchesLegacy() {
        var eng = new BacktestEngine(10000, 0.001, 0.0002);
        for (long seed : new long[]{1, 3, 9}) {
            var bars = path(seed);
            var plain = eng.run(bars, new MovingAverageStrategy("m", 5, 20, 1));
            var off = eng.run(bars, new MovingAverageStrategy("m", 5, 20, 1), new BacktestEngine.RiskSpec("ma", ALL_OFF, 0, 0));
            assertEquals(plain.metrics().get("total_return"), off.metrics().get("total_return"), "seed " + seed);
            assertEquals(plain.trades(), off.trades());
        }
    }

    /** 第 20 根買進，之後橫盤，第 40 根跳空大跌：停損以開盤價成交（不是停損價，也不是收盤價）。 */
    @Test
    void gapThroughStopFillsAtOpen() {
        List<Kline> bars = new ArrayList<>();
        for (int i = 0; i < 40; i++) bars.add(k(i, 100, 100.5, 99.5, 100));   // ATR = 1
        bars.add(k(40, 90, 91, 89, 90));
        Strategy buyAt20 = new Strategy() {
            public String id() { return "b"; }
            public void onBar(Kline b, StrategyContext ctx) {
                if (b.openTime() == 20 * 60000L) ctx.submit(new OrderIntent("c", "b", "X", OrderSide.BUY, OrderType.MARKET, null, 1, false, "SPOT", "main"));
            }
        };
        var r = new BacktestEngine(10000, 0, 0).run(bars, buyAt20,
                new BacktestEngine.RiskSpec("any", Map.of("regimeFilter", 0, "blockHighVol", 0), 10, 0));
        var log = r.tradeLog();
        assertEquals(2, log.size());
        assertEquals(OrderSide.SELL, log.get(1).side());
        assertEquals(90, log.get(1).price(), 1e-9, "停損價 98 被跳空跳過 → 開盤價 90 成交");
        assertEquals(1, ((Map<?, ?>) r.risk().get("exits")).get("停損"));
        assertEquals(1000, log.get(0).qty() * log.get(0).price(), 1e-6, "每單名目 = 權益 10%");
    }

    @Test
    void breakerStopsNewEntriesAfterLosingStreak() {
        // 每 30 根：買進後立刻跌 3% 觸發停損 → 連虧；第 3 筆後應該停止進場
        List<Kline> bars = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            boolean crash = i % 30 == 25;
            bars.add(crash ? k(i, 100, 100, 96, 97) : k(i, 100, 100.3, 99.7, 100));
        }
        Strategy buyEvery30 = new Strategy() {
            public String id() { return "b"; }
            public void onBar(Kline b, StrategyContext ctx) {
                long i = b.openTime() / 60000L;
                if (i >= 20 && i % 30 == 20) ctx.submit(new OrderIntent("c" + i, "b", "X", OrderSide.BUY, OrderType.MARKET, null, 1, false, "SPOT", "main"));
            }
        };
        var r = new BacktestEngine(10000, 0, 0).run(bars, buyEvery30,
                new BacktestEngine.RiskSpec("any", Map.of("regimeFilter", 0, "blockHighVol", 0, "maxConsecLosses", 3, "maxLossUsdt", 0, "cooldownBars", 0), 10, 0));
        assertNotNull(r.risk().get("pausedReason"));
        assertEquals(3, r.risk().get("roundTrips"));
        assertTrue(((Map<?, ?>) r.risk().get("blocked")).containsKey("熔斷暫停"));
    }
}
