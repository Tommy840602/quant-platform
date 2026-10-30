package com.quant.common.binance;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BinanceClockTest {

    @Test
    void offsetUsesRoundTripMidpoint() throws Exception {
        AtomicLong local = new AtomicLong(1_000_000);
        // 本機慢 3 秒；fetch 期間本機走 200ms（往返延遲），伺服器回的是中點時間
        BinanceClock c = new BinanceClock(base -> { local.addAndGet(200); return 1_000_100 + 3_000; }, local::get);
        long off = c.sync("https://api.binance.com");
        assertEquals(3_000, off);
        assertEquals(local.get() + 3_000, c.now("https://api.binance.com"));
        assertTrue(c.synced("https://api.binance.com"));
    }

    @Test
    void unsyncedBaseFallsBackToLocalTime() {
        AtomicLong local = new AtomicLong(42);
        BinanceClock c = new BinanceClock(base -> { throw new IllegalStateException("down"); }, local::get);
        assertEquals(42, c.now("https://x"));
        assertNull(c.trySync("https://x"));
        assertEquals(0, c.offset("https://x"));
    }

    @Test
    void offsetsAreTrackedPerBaseUrl() throws Exception {
        AtomicLong local = new AtomicLong(0);
        BinanceClock c = new BinanceClock(base -> base.contains("fapi") ? 500 : -700, local::get);
        c.sync("https://fapi.binance.com"); c.sync("https://api.binance.com");
        assertEquals(500, c.offset("https://fapi.binance.com"));
        assertEquals(-700, c.offset("https://api.binance.com"));
    }

    @Test
    void failedResyncKeepsPreviousOffset() throws Exception {
        AtomicLong local = new AtomicLong(0);
        AtomicLong fail = new AtomicLong(0);
        BinanceClock c = new BinanceClock(base -> { if (fail.get() > 0) throw new RuntimeException("x"); return 1_500; }, local::get);
        c.sync("b"); fail.set(1);
        assertNull(c.trySync("b"));
        assertEquals(1_500, c.offset("b"));
    }

    @Test
    void timestampErrorDetection() {
        assertTrue(BinanceClock.isTimestampError(-1021, null));
        assertTrue(BinanceClock.isTimestampError(0, "{\"code\":-1021,\"msg\":\"Timestamp for this request is outside of the recvWindow.\"}"));
        assertFalse(BinanceClock.isTimestampError(-2010, "{\"code\":-2010}"));
        assertTrue(BinanceClock.skewTooLarge(1_001));
        assertFalse(BinanceClock.skewTooLarge(-999));
    }

    @Test
    void timePathBySite() {
        assertEquals("/api/v3/time", BinanceClock.timePath("https://testnet.binance.vision"));
        assertEquals("/fapi/v1/time", BinanceClock.timePath("https://testnet.binancefuture.com"));
        assertEquals("/fapi/v1/time", BinanceClock.timePath("https://fapi.binance.com"));
    }
}
