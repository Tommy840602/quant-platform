package com.quant.backtest.service;

import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.Kline;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BarSourceTest {

    MarketClient market = mock(MarketClient.class);

    private static List<Kline> bars(int n, boolean lastClosed) {
        List<Kline> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Kline("BTCUSDT", "1m", i * 60_000L, 1, 2, 0.5, 1.5, 1, i * 60_000L + 59_999, i < n - 1 || lastClosed));
        return out;
    }

    @Test
    void tooFewBarsIsAnErrorNotSyntheticData() {
        when(market.klines(any(), any(), anyInt())).thenReturn(ApiResponse.ok(bars(5, true)));
        BarSource src = new BarSource(market);
        var ex = assertThrows(BarSource.NoDataException.class, () -> src.latest("BTCUSDT", "1m", 300));
        assertTrue(ex.getMessage().contains("只有 5 根"));
    }

    @Test
    void marketDownIsAnError() {
        when(market.klines(any(), any(), anyInt())).thenThrow(new RuntimeException("connection refused"));
        assertThrows(BarSource.NoDataException.class, () -> new BarSource(market).latest("BTCUSDT", "1m", 300));
    }

    @Test
    void unclosedLastBarIsDropped() {
        when(market.klines(any(), any(), anyInt())).thenReturn(ApiResponse.ok(bars(50, false)));
        BarSource.Bars b = new BarSource(market).latest("BTCUSDT", "1m", 50);
        assertEquals(49, b.bars().size());
        assertEquals("market-data:latest", b.dataSource());
    }

    @Test
    void closesBecomeFlatBarsAndAreLabelled() {
        List<Double> closes = new ArrayList<>(); for (int i = 0; i < 20; i++) closes.add(100.0 + i);
        BarSource.Bars b = BarSource.fromCloses("ETHUSDT", "5m", closes, 300_000);
        assertEquals(20, b.bars().size());
        assertEquals("closes(O=H=L=C)", b.dataSource());
        Kline k = b.bars().get(3);
        assertEquals(103, k.open()); assertEquals(103, k.high()); assertEquals(103, k.low()); assertEquals(103, k.close());
        assertEquals(300_000, b.bars().get(1).openTime() - b.bars().get(0).openTime());
        assertThrows(BarSource.NoDataException.class, () -> BarSource.fromCloses("X", "1m", List.of(1.0, 2.0), 60_000));
    }
}
