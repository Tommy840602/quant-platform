package com.quant.backtest.engine;

import com.quant.common.dto.Kline;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BacktestShortTest {

    static Kline bar(int i, double o, double h, double l, double c) {
        return new Kline("X", "1m", i * 60_000L, o, h, l, c, 1, i * 60_000L + 59_999, true);
    }

    /** 先平 40 根、再單邊下跌 80 根、再平 40 根。 */
    static List<Kline> downtrend() {
        List<Kline> out = new ArrayList<>(); double p = 100;
        for (int i = 0; i < 40; i++) out.add(bar(i, p, p * 1.002, p * 0.998, p));
        for (int i = 40; i < 120; i++) { double o = p; p *= 0.995; out.add(bar(i, o, o * 1.001, p * 0.999, p)); }
        for (int i = 120; i < 160; i++) out.add(bar(i, p, p * 1.002, p * 0.998, p));
        return out;
    }

    static Map<String, Object> ma() { Map<String, Object> m = new HashMap<>(Map.of("fast", 3, "slow", 8, "qty", 0.01)); return m; }

    @Test
    void shortAccountingMatchesFuturesPnl() {
        BacktestContext c = new BacktestContext(1000, 0.001, 0);
        c.updatePrice("X", 100);
        assertNotNull(c.openShort("X", 100, 5));            // 空 5 顆 = 500 名目
        assertEquals(-5, c.position("X"), 1e-12);
        c.updatePrice("X", 90);
        // 1000 − 開倉手續費 0.5 + 價差 5×10
        assertEquals(1000 - 0.5 + 50, c.equity(), 1e-9);
        var t = c.cover("X", 90);
        assertEquals("平空", t.action());
        assertEquals(1000 - 0.5 + 50 - 0.45, c.equity(), 1e-9);
        assertEquals(0, c.position("X"), 1e-12);
    }

    @Test
    void shortNotionalCappedAtEquity() {
        BacktestContext c = new BacktestContext(1000, 0, 0);
        c.updatePrice("X", 100);
        c.openShort("X", 100, 1_000_000);
        assertEquals(10, -c.position("X"), 1e-9, "1 倍：空單名目 ≤ 權益");
    }

    @Test
    void twinProfitsInDowntrendWhereLongOnlyCannot() {
        var bars = downtrend();
        var eng = new BacktestEngine(10_000, 0.0005, 0);
        var spec = new BacktestEngine.RiskSpec("ma", Map.of("regimeFilter", 0.0, "blockHighVol", 0.0), 0, 0);
        var longOnly = eng.run(bars, StrategyFactory.build("ma", "a", ma()), spec);
        var withShort = eng.run(bars, StrategyFactory.build("ma", "a", ma()), StrategyFactory.build("ma", "b", ma()), spec);
        double lo = (double) longOnly.metrics().get("total_return"), ws = (double) withShort.metrics().get("total_return");
        assertTrue(ws > 0.05, "下跌 33% 的行情做空應該賺：" + ws + " " + withShort.risk());
        assertTrue(ws > lo + 0.05, "比只做多好：long=" + lo + " short=" + ws);
        assertTrue(withShort.tradeLog().stream().anyMatch(t -> "開空".equals(t.action())));
    }

    @Test
    void shortExitTriggersIntraBarOnSpike() {
        List<Kline> bars = new ArrayList<>(downtrend().subList(0, 70));   // 已進入下跌、開了空
        double p = bars.get(bars.size() - 1).close();
        bars.add(bar(70, p, p * 1.20, p, p * 1.15));                        // 急拉 20%
        var eng = new BacktestEngine(10_000, 0, 0);
        var spec = new BacktestEngine.RiskSpec("ma", Map.of("regimeFilter", 0.0, "blockHighVol", 0.0, "stopAtr", 2.0), 0, 0);
        var r = eng.run(bars, StrategyFactory.build("ma", "a", ma()), StrategyFactory.build("ma", "b", ma()), spec);
        @SuppressWarnings("unchecked") Map<String, Integer> exits = (Map<String, Integer>) r.risk().get("exits");
        assertTrue(exits.containsKey("空單移動停利"), "急拉先碰到較近的移動停利（不是更遠的固定停損）：" + exits);
        var last = r.tradeLog().get(r.tradeLog().size() - 1);
        assertEquals("平空", last.action());
        assertTrue(last.price() < p * 1.05, "以觸發價成交，不是收盤價 " + last.price() + " vs " + p);
        assertTrue(((Number) r.risk().get("shortTrips")).intValue() == 1);
    }

    @Test
    void spotBehaviourUnchangedWithoutTwin() {
        var bars = downtrend();
        var eng = new BacktestEngine(10_000, 0.001, 0);
        var spec = new BacktestEngine.RiskSpec("ma", Map.of(), 0, 0);
        var a = eng.run(bars, StrategyFactory.build("ma", "a", ma()), spec);
        var b = eng.run(bars, StrategyFactory.build("ma", "a", ma()), null, spec);
        assertEquals(a.equity(), b.equity());
        assertTrue(a.tradeLog().stream().noneMatch(t -> "開空".equals(t.action())));
    }
}
