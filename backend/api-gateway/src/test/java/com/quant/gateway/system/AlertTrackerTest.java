package com.quant.gateway.system;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AlertTrackerTest {

    @Test
    void alertsOnceAfterGraceThenRecovers() {
        AlertTracker t = new AlertTracker(120_000);
        assertTrue(t.update("k", "svc", true, "x", 0).isEmpty(), "剛壞不叫");
        assertTrue(t.update("k", "svc", true, "x", 90_000).isEmpty());
        List<String> a = t.update("k", "svc", true, "x", 120_000);
        assertEquals(1, a.size()); assertTrue(a.get(0).startsWith("🔴 svc"), a.get(0));
        assertTrue(t.update("k", "svc", true, "x", 300_000).isEmpty(), "只叫一次");
        List<String> r = t.update("k", "svc", false, null, 360_000);
        assertEquals(1, r.size()); assertTrue(r.get(0).contains("已恢復") && r.get(0).contains("6 分鐘"), r.get(0));
    }

    @Test
    void shortBlipIsSilentBothWays() {
        AlertTracker t = new AlertTracker(120_000);
        t.update("k", "svc", true, "x", 0);
        assertTrue(t.update("k", "svc", false, null, 60_000).isEmpty(), "沒叫過就不發恢復");
        assertTrue(t.update("k", "svc", true, "x", 70_000).isEmpty(), "重新計時");
        assertTrue(t.update("k", "svc", true, "x", 180_000).isEmpty());
        assertEquals(1, t.update("k", "svc", true, "x", 190_000).size());
    }

    @Test
    void watchdogFlagsDownServiceAndStaleMarket() {
        Watchdog w = new Watchdog(null, "", "", true);
        List<Map<String, Object>> svcs = List.of(
                Map.<String, Object>of("name", "strategy-service", "status", "UP"),
                Map.<String, Object>of("name", "risk-service", "status", "DOWN", "error", "ConnectException: refused"));
        assertTrue(w.evaluate(svcs, 5, 0).isEmpty());
        List<String> m = w.evaluate(svcs, 300, 130_000);
        assertEquals(1, m.size(), "risk 已壞 130 秒；行情剛變舊還沒滿 2 分鐘");
        assertTrue(m.get(0).contains("risk-service") && m.get(0).contains("refused"));
        List<String> later = w.evaluate(svcs, 420, 260_000);
        assertEquals(1, later.size()); assertTrue(later.get(0).contains("行情資料"), later.get(0));
    }
}
