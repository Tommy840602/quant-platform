package com.quant.order.service;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MonthlyPnlTest {
    static final ZoneId TPE = ZoneId.of("Asia/Taipei");
    static long t(int y, int mo, int d) { return ZonedDateTime.of(y, mo, d, 12, 0, 0, 0, TPE).toInstant().toEpochMilli(); }
    static MonthlyPnl.Fill f(long ts, String bot, String mkt, boolean buy, double q, double px) { return new MonthlyPnl.Fill(ts, bot, mkt, "BTCUSDT", buy, q, px); }

    @Test
    void spotFifoBookedInMonthOfClose() {
        var rows = MonthlyPnl.compute(List.of(
                f(t(2026, 9, 28), "a", "SPOT", true, 1, 100),
                f(t(2026, 10, 2), "a", "SPOT", false, 1, 110),     // +10，記 10 月
                f(t(2026, 10, 5), "a", "SPOT", true, 2, 100),
                f(t(2026, 10, 6), "a", "SPOT", false, 1, 90)),     // −10
                TPE);
        var oct = rows.stream().filter(r -> r.month.equals("2026-10")).findFirst().orElseThrow();
        assertEquals(0, oct.realized, 1e-9);
        assertEquals(2, oct.trips); assertEquals(1, oct.wins);
        assertEquals((110 + 200 + 90) * 0.001, oct.fees, 1e-9);
        var sep = rows.stream().filter(r -> r.month.equals("2026-09")).findFirst().orElseThrow();
        assertEquals(0, sep.trips, "9 月只有買進，沒有平倉");
        assertEquals("2026-10", rows.get(0).month, "新的月份在前");
    }

    @Test
    void futuresShortIsSignedAndUsesFuturesFee() {
        var rows = MonthlyPnl.compute(List.of(
                f(t(2026, 11, 1), "s", "USDT_M", false, 0.5, 200),   // 開空
                f(t(2026, 11, 2), "s", "USDT_M", true, 0.5, 180)),   // 回補 +10
                TPE);
        assertEquals(1, rows.size());
        assertEquals(10, rows.get(0).realized, 1e-9);
        assertEquals((100 + 90) * 0.0005, rows.get(0).fees, 1e-9);
        assertEquals("USDT_M", rows.get(0).market);
    }

    @Test
    void spotSellWithoutPositionIsIgnoredAndManualLabelled() {
        var rows = MonthlyPnl.compute(List.of(f(t(2026, 11, 1), null, "SPOT", false, 1, 100), f(t(2026, 11, 2), null, "SPOT", true, 1, 90)), TPE);
        assertEquals("(手動)", rows.get(0).strategy);
        assertEquals(0, rows.get(0).trips, "現貨沒部位的 SELL 不變空單");
    }

    @Test
    void csvHasBomHeaderAndTotals() {
        var rows = MonthlyPnl.compute(List.of(f(t(2026, 11, 1), "a,b", "SPOT", true, 1, 100), f(t(2026, 11, 3), "a,b", "SPOT", false, 1, 105)), TPE);
        String csv = MonthlyPnl.csv(rows);
        assertTrue(csv.startsWith("﻿month,strategy"));
        assertTrue(csv.contains("\"a,b\""), "含逗號要加引號");
        var tot = MonthlyPnl.totals(rows);
        assertEquals(5.0, tot.get(0).get("realizedPnl"));
    }
}
