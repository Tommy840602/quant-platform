package com.quant.account.service;

import com.quant.account.entity.PositionEntity;
import com.quant.account.repository.PositionRepository;
import com.quant.common.enums.OrderSide;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PositionStoreTest {

    @Test
    void sellReducesPosition() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findById(any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        PositionStore store = new PositionStore(repo);

        PositionEntity p = store.onFilled("main", "BTCUSDT", OrderSide.BUY, 1.0, 100);
        assertEquals(1.0, p.getQuantity().doubleValue(), 1e-12);
        assertEquals(100.0, p.getAvgPrice().doubleValue(), 1e-12);

        when(repo.findById(any())).thenReturn(Optional.of(p));
        p = store.onFilled("main", "BTCUSDT", OrderSide.SELL, 0.4, 150);
        assertEquals(0.6, p.getQuantity().doubleValue(), 1e-12);
        assertEquals(100.0, p.getAvgPrice().doubleValue(), 1e-12, "減倉不改均價");

        p = store.onFilled("main", "BTCUSDT", OrderSide.SELL, 0.6, 150);
        assertEquals(0.0, p.getQuantity().doubleValue(), 1e-12);
        assertEquals(0.0, p.getAvgPrice().doubleValue(), 1e-12);
    }

    @Test
    void nullSideIsRejected() {
        PositionRepository repo = mock(PositionRepository.class);
        assertNull(new PositionStore(repo).onFilled("main", "BTCUSDT", null, 1, 100));
        verify(repo, never()).save(any());
    }

    @Test
    void avgCostRules() {
        assertEquals(100, PositionStore.computeAvg(0, 0, 1, 100), 1e-9, "從零開倉");
        assertEquals(110, PositionStore.computeAvg(1, 100, 1, 120), 1e-9, "同向加倉加權");
        assertEquals(110, PositionStore.computeAvg(2, 110, -1, 300), 1e-9, "減倉不變");
        assertEquals(0, PositionStore.computeAvg(1, 110, -1, 300), 1e-9, "歸零");
        assertEquals(300, PositionStore.computeAvg(1, 110, -2, 300), 1e-9, "穿零翻空 → 新均價");
        assertEquals(110, PositionStore.computeAvg(1, 110, 1, 0), 1e-9, "無價格 → 不動");
    }
}
