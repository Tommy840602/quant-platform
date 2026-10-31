package com.quant.risk.service;

import com.quant.common.dto.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RiskEngineTest {

    KillSwitch kill = mock(KillSwitch.class);
    RiskLimits limits = mock(RiskLimits.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String, String> ops = mock(ValueOperations.class);
    AccountClient account = mock(AccountClient.class);
    Map<String, String> kv = new HashMap<>();   // 假 Redis
    RiskEngine engine;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(i -> kv.get(i.getArgument(0, String.class)));
        doAnswer(i -> { kv.put(i.getArgument(0), i.getArgument(1)); return null; }).when(ops).set(anyString(), anyString());
        doAnswer(i -> { kv.put(i.getArgument(0), i.getArgument(1)); return null; }).when(ops).set(anyString(), anyString(), anyLong(), any());
        when(ops.increment(anyString(), anyLong())).thenAnswer(i -> { String k = i.getArgument(0); long v = (long) Double.parseDouble(kv.getOrDefault(k, "0")) + i.getArgument(1, Long.class); kv.put(k, String.valueOf(v)); return v; });
        when(ops.increment(anyString(), anyDouble())).thenAnswer(i -> { String k = i.getArgument(0); double v = Double.parseDouble(kv.getOrDefault(k, "0")) + i.getArgument(1, Double.class); kv.put(k, String.valueOf(v)); return v; });
        when(kill.isOn()).thenReturn(false);
        when(limits.maxOrderQty()).thenReturn(0.0);
        when(limits.maxPositionPct()).thenReturn(0.5);
        when(limits.maxDailyLossPct()).thenReturn(0.03);
        when(limits.maxDailyOrders()).thenReturn(3);
        when(limits.maxDailyNotional()).thenReturn(0.0);
        when(limits.maxPriceDeviationPct()).thenReturn(0.05);
        when(limits.failClosed()).thenReturn(true);
        kv.put("price:BTCUSDT", "100");
        balance(10000, 2000);
        engine = new RiskEngine(kill, limits, redis, account, () -> LocalDate.of(2026, 10, 31));
    }

    private void balance(double total, double btcValue) {
        when(account.balance()).thenReturn(ApiResponse.ok(Map.of("totalUsdt", total,
                "balances", List.of(Map.of("asset", "BTC", "valueUsdt", btcValue), Map.of("asset", "USDT", "valueUsdt", total - btcValue)))));
    }
    private static Map<String, Object> buy(double qty) { return intent("BUY", qty, null); }
    private static Map<String, Object> intent(String side, double qty, Double price) {
        Map<String, Object> m = new HashMap<>();
        m.put("symbol", "BTCUSDT"); m.put("side", side); m.put("quantity", qty); m.put("account", "main");
        if (price != null) m.put("price", price);
        return m;
    }

    @Test
    void normalBuyApprovedAndCounted() {
        assertTrue(engine.evaluate(buy(1)).approved());
        assertEquals("1", kv.get("risk:orders:main:2026-10-31"));
        assertEquals(100.0, Double.parseDouble(kv.get("risk:notional:main:2026-10-31")), 1e-9);
    }

    @Test
    void dailyOrderCountCap() {
        for (int i = 0; i < 3; i++) assertTrue(engine.evaluate(intent("SELL", 1, null)).approved());
        RiskEngine.RiskDecision d = engine.evaluate(intent("SELL", 1, null));
        assertFalse(d.approved()); assertTrue(d.reason().contains("次數"));
    }

    @Test
    void dailyNotionalCap() {
        when(limits.maxDailyNotional()).thenReturn(250.0);
        assertTrue(engine.evaluate(buy(1)).approved());   // 100
        assertTrue(engine.evaluate(buy(1)).approved());   // 200
        assertFalse(engine.evaluate(buy(1)).approved());  // 300 > 250
    }

    @Test
    void concentrationBlocksBuyThatWouldExceedCap() {
        // 權益 10000，已持 2000 非穩定幣；買 35 BTC×100 = 3500 → 55% > 50%
        RiskEngine.RiskDecision d = engine.evaluate(buy(35));
        assertFalse(d.approved()); assertTrue(d.reason().contains("持倉佔比"));
        assertTrue(engine.evaluate(buy(29)).approved(), "4900/10000 = 49% 放行");
        assertTrue(engine.evaluate(intent("SELL", 100, null)).approved(), "SELL 不看集中度");
    }

    @Test
    void dailyLossLockBlocksBuyButAllowsSell() {
        assertTrue(engine.evaluate(buy(1)).approved());            // 記日初權益 10000
        balance(9600, 2000);                                      // −4%
        RiskEngine.RiskDecision d = engine.evaluate(buy(1));
        assertFalse(d.approved()); assertTrue(d.reason().contains("今日虧損"));
        assertTrue(engine.evaluate(intent("SELL", 1, null)).approved());
        balance(9800, 2000);                                      // −2% 恢復
        assertTrue(engine.evaluate(buy(1)).approved());
    }

    @Test
    void limitPriceFarFromMarketIsFatFinger() {
        RiskEngine.RiskDecision d = engine.evaluate(intent("BUY", 1, 80.0));   // 20% 偏離
        assertFalse(d.approved()); assertTrue(d.reason().contains("胖手指"));
        assertTrue(engine.evaluate(intent("BUY", 1, 97.0)).approved());
    }

    @Test
    void accountDownFailsClosedForBuyOnly() {
        when(account.balance()).thenThrow(new RuntimeException("down"));
        RiskEngine.RiskDecision d = engine.evaluate(buy(1));
        assertFalse(d.approved()); assertTrue(d.reason().contains("fail-closed"));
        assertTrue(engine.evaluate(intent("SELL", 1, null)).approved());
        when(limits.failClosed()).thenReturn(false);
        assertTrue(engine.evaluate(buy(1)).approved());
    }

    @Test
    void killSwitchFirst() {
        when(kill.isOn()).thenReturn(true); when(kill.reason()).thenReturn("dd");
        assertFalse(engine.evaluate(buy(1)).approved());
        verifyNoInteractions(account);
    }

    @Test
    void statusReportsCountersAndPct() {
        engine.evaluate(buy(1));
        Map<String, Object> st = engine.status("main");
        assertEquals(1L, st.get("ordersToday"));
        assertEquals(0.2, (double) st.get("positionPct"), 1e-9);
        assertEquals(10000.0, (double) st.get("dayStartEquity"), 1e-9);
    }
}
