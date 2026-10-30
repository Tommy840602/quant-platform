package com.quant.order.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class UdsRegistryTest {

    NotifyClient notify = mock(NotifyClient.class);

    @Test
    void unknownMarketIsNeverConnected() {
        UdsRegistry r = new UdsRegistry(notify);
        assertFalse(r.isConnected(UdsRegistry.SPOT));
        assertFalse(r.isExpected(UdsRegistry.SPOT));
    }

    @Test
    void expectedWithoutConnectionIsReportedAsDown() {
        UdsRegistry r = new UdsRegistry(notify);
        r.expect(UdsRegistry.SPOT);
        assertTrue(r.isExpected(UdsRegistry.SPOT));
        assertFalse(r.isConnected(UdsRegistry.SPOT));
        r.markConnected(UdsRegistry.SPOT);
        assertTrue(r.isConnected(UdsRegistry.SPOT));
        r.markDisconnected(UdsRegistry.SPOT, "closed");
        assertFalse(r.isConnected(UdsRegistry.SPOT));
        @SuppressWarnings("unchecked")
        Map<String, Object> spot = (Map<String, Object>) r.status().get(UdsRegistry.SPOT);
        assertEquals("closed", spot.get("lastError"));
    }

    @Test
    void watchdogStaysQuietWhenNothingIsExpected() {
        UdsRegistry r = new UdsRegistry(notify);
        r.markDisconnected(UdsRegistry.SPOT, "never enabled");
        r.watchdog();
        verify(notify, never()).send(anyMap());
    }
}
