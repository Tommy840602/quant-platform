package com.quant.marketdata.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IntervalsTest {
    @Test
    void parsesBinanceIntervals() {
        assertEquals(60_000, Intervals.millis("1m"));
        assertEquals(15 * 60_000, Intervals.millis("15m"));
        assertEquals(4 * 3_600_000, Intervals.millis("4h"));
        assertEquals(86_400_000, Intervals.millis("1d"));
        assertThrows(IllegalArgumentException.class, () -> Intervals.millis("1x"));
        assertThrows(IllegalArgumentException.class, () -> Intervals.millis("m"));
    }
}
