package com.quant.strategy.service;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BanditAllocatorTest {

    @Test
    void noDataMeansEqualAllocation() {
        BanditAllocator b = new BanditAllocator(new Random(1));
        Map<String, Double> a = b.allocations(List.of("a", "b"), 0.25, 2.5);
        assertEquals(1.0, a.get("a"), 0.1);
        assertEquals(1.0, a.get("b"), 0.1);
    }

    @Test
    void consistentWinnerGetsMoreCapital_loserKeepsFloor() {
        BanditAllocator b = new BanditAllocator(new Random(2));
        for (int i = 0; i < 20; i++) { b.record("good", 0.01 + (i % 3) * 0.001); b.record("bad", -0.01 - (i % 3) * 0.001); }
        Map<String, Double> p = b.pBest(List.of("good", "bad", "new"));
        assertTrue(p.get("good") > 0.6, "穩定獲利的機器人最可能是最好的: " + p);
        Map<String, Double> a = b.allocations(List.of("good", "bad", "new"), 0.25, 2.5);
        assertTrue(a.get("good") > 1.5, "a=" + a);
        assertEquals(0.25, a.get("bad"), 1e-9, "表現差的保留下限繼續探索");
    }

    @Test
    void oneLuckyTradeDoesNotDominate() {
        BanditAllocator b = new BanditAllocator(new Random(3));
        b.record("lucky", 0.05);   // 一筆 +5%
        Map<String, Double> p = b.pBest(List.of("lucky", "other"));
        assertTrue(p.get("lucky") < 0.95, "先驗 5 筆虛擬交易會壓住單筆運氣: " + p);
    }

    @Test
    void windowKeepsRecentTradesOnly() {
        BanditAllocator b = new BanditAllocator();
        for (int i = 0; i < 80; i++) b.record("x", 0.001);
        assertEquals(BanditAllocator.WINDOW, b.n("x"));
    }
}
