package com.quant.notification.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlertGateTest {

    SlackNotifier slack = mock(SlackNotifier.class);
    AtomicLong now = new AtomicLong(1_000_000);

    private AlertGate gate() {
        AlertGate g = new AlertGate(slack, now::get);
        ReflectionTestUtils.setField(g, "windowSec", 600L);
        ReflectionTestUtils.setField(g, "dedupeEnabled", true);
        return g;
    }

    @Test
    void sameAlertWithDifferentNumbersIsSuppressedWithinWindow() {
        AlertGate g = gate();
        assertTrue(g.publish(":rotating_light: User Data Stream 未連線 SPOT（connect error: timeout 3000ms）", "x"));
        assertFalse(g.publish(":rotating_light: User Data Stream 未連線 SPOT（connect error: timeout 4500ms）", "x"));
        assertFalse(g.publish(":rotating_light: User Data Stream 未連線 SPOT（connect error: timeout 9000ms）", "x"));
        verify(slack, times(1)).send(anyString());
        assertEquals(2L, g.stats().get("suppressedToday"));
    }

    @Test
    void afterWindowNextOneGoesOutWithSuppressedCount() {
        AlertGate g = gate();
        g.publish("⚠️ 本機時鐘與幣安相差 1200 ms", "clock");
        g.publish("⚠️ 本機時鐘與幣安相差 1300 ms", "clock");
        now.addAndGet(601_000);
        assertTrue(g.publish("⚠️ 本機時鐘與幣安相差 1400 ms", "clock"));
        verify(slack).send(argThat(t -> t.contains("1400") && t.contains("另有 1 則已壓抑")));
    }

    @Test
    void differentAlertsAreIndependent() {
        AlertGate g = gate();
        assertTrue(g.publish(":lock: 已切回 Dry-Run", "a"));
        assertTrue(g.publish(":rotating_light: 熔斷觸發 回撤 5%", "b"));
        verify(slack, times(2)).send(anyString());
    }

    @Test
    void fingerprintStripsNumbers() {
        assertEquals(AlertGate.fingerprint("成交 BTCUSDT 0.01 @ 60000.5"), AlertGate.fingerprint("成交 BTCUSDT 0.02 @ 61234.1"));
        assertNotEquals(AlertGate.fingerprint("成交 BTCUSDT"), AlertGate.fingerprint("成交 ETHUSDT"));
    }

    @Test
    void digestCountsCategories() {
        AlertGate g = gate();
        g.publish("x1", "真實成交"); g.countOnly("模擬成交"); g.countOnly("模擬成交");
        String d = g.digestText();
        assertTrue(d.contains("真實成交：1") && d.contains("模擬成交：2"));
    }
}
