package com.quant.marketdata.service;

import com.quant.common.dto.Kline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KlineBackfillServiceTest {

    // 三根 1m K 線：openTime 60000/120000/180000；最後一根尚未收盤（closeTime 239999 >= now 200000）
    private static final String JSON = """
        [[60000,"100","101","99","100.5","10",119999,"0",1,"0","0","0"],
         [120000,"100.5","102","100","101","12",179999,"0",1,"0","0","0"],
         [180000,"101","103","100","102","5",239999,"0",1,"0","0","0"]]
        """;

    @Test
    void keepsOnlyClosedBarsAfterSince() throws Exception {
        List<Kline> out = KlineBackfillService.parse(JSON, "BTCUSDT", "1m", 60000, 200000);
        assertEquals(1, out.size(), "60000 已有、180000 未收盤 → 只補 120000");
        Kline k = out.get(0);
        assertEquals(120000, k.openTime());
        assertEquals(179999, k.closeTime());
        assertTrue(k.closed());
        assertEquals(101, k.close(), 1e-9);
        assertEquals("BTCUSDT", k.symbol());
    }

    @Test
    void everythingClosedWhenNowIsLate() throws Exception {
        List<Kline> out = KlineBackfillService.parse(JSON, "BTCUSDT", "1m", 0, 300000);
        assertEquals(3, out.size());
        assertEquals(60000, out.get(0).openTime());
        assertEquals(180000, out.get(2).openTime());
    }

    @Test
    void nothingMissedReturnsEmpty() throws Exception {
        assertTrue(KlineBackfillService.parse(JSON, "BTCUSDT", "1m", 180000, 300000).isEmpty());
        assertTrue(KlineBackfillService.parse("[]", "BTCUSDT", "1m", 0, 300000).isEmpty());
    }
}
