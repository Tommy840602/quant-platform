package com.quant.account.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FuturesAccountServiceTest {

    static final String BODY = """
        {"totalWalletBalance":"500.0","totalUnrealizedProfit":"-12.5","totalMarginBalance":"487.5","availableBalance":"300.0",
         "positions":[
           {"symbol":"BTCUSDT","positionAmt":"-0.002","entryPrice":"70000","notional":"-141.0","unrealizedProfit":"-1.0","leverage":"1"},
           {"symbol":"ETHUSDT","positionAmt":"0","entryPrice":"0","notional":"0","unrealizedProfit":"0","leverage":"20"},
           {"symbol":"SOLUSDT","positionAmt":"1.5","entryPrice":"150","notional":"230.0","unrealizedProfit":"5.0","leverage":"1"}]}""";

    @Test
    void parsesMarginBalanceAndNonZeroPositions() throws Exception {
        var s = FuturesAccountService.parse(BODY, 0);
        assertEquals(487.5, s.marginBalance(), 1e-9);
        assertEquals(-12.5, s.unrealizedPnl(), 1e-9);
        assertEquals(2, s.positions().size(), "0 持倉不列");
        assertEquals("SOLUSDT", s.positions().get(0).get("symbol"), "依名目大小排序");
        assertEquals("SHORT", s.positions().get(1).get("side"));
        assertEquals(371.0, s.grossNotional(), 1e-9, "空單名目取絕對值");
    }

    @Test
    void rejectsErrorBody() {
        assertThrows(Exception.class, () -> FuturesAccountService.parse("{\"code\":-2015,\"msg\":\"Invalid API-key\"}", 0));
    }

    /** 可控的假服務：snapshot() 回固定值或丟錯。 */
    static FuturesAccountService fake(BinanceAccountService spot, AtomicLong clock, String[] body) {
        return new FuturesAccountService(spot, clock::get) {
            { testKey = "k"; testSecret = "s"; mainKey = ""; mainSecret = ""; }
            @Override public synchronized java.util.Optional<Snapshot> snapshot() {
                try { return java.util.Optional.of(parse(body[0], clock.get())); }
                catch (Exception e) { return java.util.Optional.empty(); }
            }
        };
    }

    @Test
    void walletAddsFuturesMarginToRealSpotTotal() {
        BinanceAccountService spot = mock(BinanceAccountService.class);
        when(spot.env()).thenReturn("testnet");
        FuturesAccountService fut = fake(spot, new AtomicLong(0), new String[]{BODY});
        Map<String, Object> out = WalletService.combine(Map.of("real", true, "totalUsdt", 1000.0, "balances", List.of()), fut);
        assertEquals(1487.5, (double) out.get("totalUsdt"), 1e-9);
        assertEquals(1000.0, (double) out.get("spotUsdt"), 1e-9);
        assertNotNull(out.get("futures"));
        assertNull(out.get("partial"));
    }

    @Test
    void walletMarksPartialWhenFuturesUnreachable() {
        BinanceAccountService spot = mock(BinanceAccountService.class);
        when(spot.env()).thenReturn("testnet");
        FuturesAccountService fut = fake(spot, new AtomicLong(0), new String[]{"oops"});
        Map<String, Object> out = WalletService.combine(Map.of("real", true, "totalUsdt", 1000.0), fut);
        assertEquals(true, out.get("partial"));
        assertEquals(1000.0, (double) out.get("totalUsdt"), 1e-9, "不亂加");
    }

    @Test
    void demoSpotBalanceIsNotMixedWithFutures() {
        BinanceAccountService spot = mock(BinanceAccountService.class);
        when(spot.env()).thenReturn("testnet");
        FuturesAccountService fut = fake(spot, new AtomicLong(0), new String[]{BODY});
        Map<String, Object> out = WalletService.combine(Map.of("real", false, "totalUsdt", 10000.0), fut);
        assertEquals(10000.0, (double) out.get("totalUsdt"), 1e-9);
        assertNull(out.get("futures"));
    }

    @Test
    void cachesThenKeepsLastGoodSnapshotForTenMinutesThenGivesUp() {
        BinanceAccountService spot = mock(BinanceAccountService.class);
        when(spot.env()).thenReturn("testnet");
        AtomicLong clock = new AtomicLong(1_000_000);
        int[] calls = {0}; boolean[] fail = {false};
        FuturesAccountService fut = new FuturesAccountService(spot, clock::get) {
            { testKey = "k"; testSecret = "s"; mainKey = ""; mainSecret = ""; }
            @Override String fetch() { calls[0]++; if (fail[0]) throw new RuntimeException("HTTP 502"); return BODY; }
        };
        assertTrue(fut.unavailable(), "還沒成功過");
        assertTrue(fut.snapshot().isPresent());
        fut.snapshot();
        assertEquals(1, calls[0], "10 秒內用快取");
        fail[0] = true;
        clock.addAndGet(5 * 60_000);
        assertTrue(fut.snapshot().isPresent(), "5 分鐘前的舊值仍可用");
        assertFalse(fut.unavailable());
        assertTrue(fut.stale());
        clock.addAndGet(6 * 60_000);
        assertTrue(fut.snapshot().isEmpty(), "超過 10 分鐘就不用了");
        assertTrue(fut.unavailable());
        assertEquals("HTTP 502", fut.lastError());
    }

    @Test
    void envSwitchDropsOldSnapshot() {
        BinanceAccountService spot = mock(BinanceAccountService.class);
        when(spot.env()).thenReturn("testnet");
        AtomicLong clock = new AtomicLong(0);
        boolean[] fail = {false};
        FuturesAccountService fut = new FuturesAccountService(spot, clock::get) {
            { testKey = "k"; testSecret = "s"; mainKey = "mk"; mainSecret = "ms"; }
            @Override String fetch() { if (fail[0]) throw new RuntimeException("x"); return BODY; }
        };
        assertTrue(fut.snapshot().isPresent());
        fail[0] = true;
        when(spot.env()).thenReturn("mainnet");
        assertTrue(fut.snapshot().isEmpty(), "測試網的數字不能當正式網用");
    }
}
