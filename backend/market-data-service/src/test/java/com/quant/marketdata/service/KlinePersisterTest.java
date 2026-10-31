package com.quant.marketdata.service;

import com.quant.common.dto.Kline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class KlinePersisterTest {

    KlineRepository repo = mock(KlineRepository.class);

    private static Kline k(long open, boolean closed) { return new Kline("BTCUSDT", "1m", open, 1, 2, 0.5, 1.5, 3, open + 59999, closed); }

    @Test
    void onlyClosedBarsArePersisted() {
        KlinePersister p = new KlinePersister(repo);
        p.enqueue(k(60000, false));
        p.enqueue(k(60000, true));
        p.flush();
        verify(repo, times(1)).upsertAll(argThat(l -> l.size() == 1 && l.get(0).openTime() == 60000));
    }

    @Test
    void dbFailureKeepsBarsQueuedForRetry() {
        when(repo.upsertAll(anyList())).thenThrow(new RuntimeException("db down")).thenReturn(2);
        KlinePersister p = new KlinePersister(repo);
        p.enqueue(k(60000, true)); p.enqueue(k(120000, true));
        p.flush();                                   // 失敗 → 留在佇列
        assertEquals(2, p.stats().get("queued"));
        p.flush();                                   // 第二次成功
        assertEquals(0, p.stats().get("queued"));
        assertEquals(2L, p.stats().get("persisted"));
        verify(repo, times(2)).upsertAll(anyList());
    }

    @Test
    void reachingBatchSizeFlushesImmediately() {
        KlinePersister p = new KlinePersister(repo);
        for (int i = 0; i < KlinePersister.FLUSH_AT; i++) p.enqueue(k(60000L * (i + 1), true));
        verify(repo, times(1)).upsertAll(argThat(l -> l.size() == KlinePersister.FLUSH_AT));
    }
}
