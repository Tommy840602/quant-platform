package com.quant.marketdata.service;

import com.quant.common.dto.Kline;
import com.quant.marketdata.config.BinanceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KlineHistoryLoaderTest {

    KlineBackfillService backfill = mock(KlineBackfillService.class);
    KlineRepository repo = mock(KlineRepository.class);
    BinanceProperties props = new BinanceProperties();
    static final long NOW = 10_000L * 60_000;   // 第 10000 分鐘

    private KlineHistoryLoader loader(int bars) {
        props.setStreams(List.of("btcusdt@kline_1m", "btcusdt@depth10@1000ms"));
        KlineHistoryLoader l = new KlineHistoryLoader(props, backfill, repo, () -> NOW);
        ReflectionTestUtils.setField(l, "historyBars", bars);
        return l;
    }

    private static List<Kline> bars(long fromOpen, int n) {
        List<Kline> out = new ArrayList<>();
        for (int i = 0; i < n; i++) { long o = fromOpen + i * 60_000L; out.add(new Kline("BTCUSDT", "1m", o, 1, 2, 0.5, 1.5, 3, o + 59999, true)); }
        return out;
    }

    @Test
    void emptyDbStartsFromHistoryWindow() {
        KlineHistoryLoader l = loader(100);
        when(repo.latestOpenTime("BTCUSDT", "1m")).thenReturn(null);
        when(backfill.fetchClosedSince(eq("BTCUSDT"), eq("1m"), eq(NOW - 100 * 60_000L), eq(NOW))).thenReturn(bars(NOW - 99 * 60_000L, 99));
        assertEquals(99, l.loadAll());
        verify(repo).upsertAll(argThat(r -> r.size() == 99));
        verify(backfill, times(1)).fetchClosedSince(any(), any(), anyLong(), anyLong());   // depth 串流不算
    }

    @Test
    void resumesFromLastBarInDbAndPaginates() {
        KlineHistoryLoader l = loader(5000);
        long last = NOW - 2500 * 60_000L;                         // DB 已到 2500 分鐘前（晚於 5000 根窗口）
        when(repo.latestOpenTime("BTCUSDT", "1m")).thenReturn(last);
        List<Kline> page1 = bars(last + 60_000, 1000), page2 = bars(last + 1001 * 60_000L, 1000), page3 = bars(last + 2001 * 60_000L, 499);
        when(backfill.fetchClosedSince(eq("BTCUSDT"), eq("1m"), eq(last), eq(NOW))).thenReturn(page1);
        when(backfill.fetchClosedSince(eq("BTCUSDT"), eq("1m"), eq(page1.get(999).openTime()), eq(NOW))).thenReturn(page2);
        when(backfill.fetchClosedSince(eq("BTCUSDT"), eq("1m"), eq(page2.get(999).openTime()), eq(NOW))).thenReturn(page3);
        assertEquals(2499, l.loadAll());
        verify(backfill, times(3)).fetchClosedSince(any(), any(), anyLong(), anyLong());
    }

    @Test
    void upToDateDbLoadsNothing() {
        KlineHistoryLoader l = loader(100);
        when(repo.latestOpenTime("BTCUSDT", "1m")).thenReturn(NOW - 60_000);
        when(backfill.fetchClosedSince(any(), any(), anyLong(), anyLong())).thenReturn(List.of());
        assertEquals(0, l.loadAll());
        verify(repo, never()).upsertAll(anyList());
    }
}
