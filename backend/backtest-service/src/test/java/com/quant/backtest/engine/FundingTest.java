package com.quant.backtest.engine;

import com.quant.common.risk.RiskRules;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class FundingTest {

    @Test
    void longPaysAndShortReceivesPositiveFunding() {
        BacktestContext c = new BacktestContext(1000, 0, 0);
        c.updatePrice("X", 100);
        c.submitAt(new com.quant.common.dto.OrderIntent("t", "bt", "X", com.quant.common.enums.OrderSide.BUY,
                com.quant.common.enums.OrderType.MARKET, null, 0, false, "SPOT", "main"), 100, 5);   // 多 5 顆
        assertEquals(0.5, c.applyFunding("X", 0.001), 1e-12, "5×100×0.1%");
        assertEquals(999.5, c.equity(), 1e-9);
        BacktestContext s = new BacktestContext(1000, 0, 0);
        s.updatePrice("X", 100);
        s.openShort("X", 100, 5);
        assertEquals(-0.5, s.applyFunding("X", 0.001), 1e-12, "空單收");
        assertEquals(1000.5, s.equity(), 1e-9);
    }

    @Test
    void engineAppliesFundingAtSettlementTimesWhileHolding() {
        var bars = BacktestShortTest.downtrend();   // 1m K，160 根
        TreeMap<Long, Double> f = new TreeMap<>();
        for (long t = 0; t <= 160 * 60_000L; t += 30 * 60_000L) f.put(t, 0.001);   // 測試用：每 30 分鐘結算 0.1%
        var eng = new BacktestEngine(10_000, 0, 0);
        var spec = new BacktestEngine.RiskSpec("ma", Map.of("regimeFilter", 0.0, "blockHighVol", 0.0), 0, 0);
        var with = eng.run(bars, StrategyFactory.build("ma", "a", BacktestShortTest.ma()), StrategyFactory.build("ma", "b", BacktestShortTest.ma()), spec, f);
        var without = eng.run(bars, StrategyFactory.build("ma", "a", BacktestShortTest.ma()), StrategyFactory.build("ma", "b", BacktestShortTest.ma()), spec, null);
        double paid = (double) with.risk().get("fundingPaid");
        assertTrue(paid < 0, "下跌行情抱空單、費率正 → 收資金費：" + paid);
        assertTrue(((Number) with.risk().get("fundingEvents")).intValue() >= 2);
        assertEquals(((Number) without.metrics().get("final_equity")).doubleValue() - paid,
                ((Number) with.metrics().get("final_equity")).doubleValue(), 1.0);
    }

    @Test
    void fundingGate() {
        Map<String, Object> p = Map.of("maxFundingPct", 0.05);
        assertNull(RiskRules.fundingBlock(true, 0.0001, p));
        assertNotNull(RiskRules.fundingBlock(true, 0.001, p), "0.1% > 0.05% 不開多");
        assertNull(RiskRules.fundingBlock(false, 0.001, p), "費率正對空單有利");
        assertNotNull(RiskRules.fundingBlock(false, -0.001, p));
        assertNull(RiskRules.fundingBlock(true, 0.01, Map.of("maxFundingPct", 0.0)), "0 = 關");
    }
}
