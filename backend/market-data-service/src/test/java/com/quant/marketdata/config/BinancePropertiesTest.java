package com.quant.marketdata.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BinancePropertiesTest {
    @Test
    void oneMinuteStreamsDeriveExtraIntervals() {
        BinanceProperties p = new BinanceProperties();
        p.setStreams(List.of("btcusdt@kline_1m", "btcusdt@depth10@1000ms", "ethusdt@kline_1m"));
        p.setExtraIntervals(List.of("5m", "1h"));
        assertEquals(List.of("btcusdt@kline_1m", "btcusdt@depth10@1000ms", "ethusdt@kline_1m",
                "btcusdt@kline_5m", "btcusdt@kline_1h", "ethusdt@kline_5m", "ethusdt@kline_1h"), p.getStreams());
        p.setExtraIntervals(List.of());
        assertEquals(3, p.getStreams().size());
    }
}
