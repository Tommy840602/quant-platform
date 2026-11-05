package com.quant.account.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PmGuardTest {

    @Test
    void judgeLevels() {
        assertEquals(PmGuard.Level.OK, PmGuard.judge("NORMAL", 3.0, 1.5, 1.2)[0]);
        assertEquals(PmGuard.Level.WARN, PmGuard.judge("NORMAL", 1.4, 1.5, 1.2)[0]);
        assertEquals(PmGuard.Level.STOP, PmGuard.judge("NORMAL", 1.1, 1.5, 1.2)[0]);
        Object[] j = PmGuard.judge("MARGIN_CALL", 5.0, 1.5, 1.2);
        assertEquals(PmGuard.Level.STOP, j[0], "狀態不是 NORMAL 就停");
        assertTrue(((String) j[1]).contains("MARGIN_CALL"));
        assertEquals(PmGuard.Level.OK, PmGuard.judge("NORMAL", null, 1.5, 1.2)[0], "沒 uniMMR（沒倉位）不鎖");
    }

    @Test
    @SuppressWarnings("unchecked")
    void stopSetsRedisFlagAndRecoveryClearsIt() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        NotifyClient notify = mock(NotifyClient.class);
        PmGuard g = new PmGuard(mock(FuturesAccountService.class), redis, notify);
        g.warnMmr = 1.5; g.stopMmr = 1.2;

        g.apply(PmGuard.Level.WARN, "uniMMR 1.40", 1000);
        verify(ops, never()).set(eq(PmGuard.KEY), anyString(), anyLong(), any());
        verify(notify, times(1)).send(anyMap());

        g.apply(PmGuard.Level.STOP, "uniMMR 1.10", 900);
        verify(ops).set(eq(PmGuard.KEY), eq("uniMMR 1.10"), eq(15L), any());
        verify(notify, times(2)).send(anyMap());

        g.apply(PmGuard.Level.STOP, "uniMMR 1.08", 890);   // 持續 STOP：續期，不重複通知
        verify(notify, times(2)).send(anyMap());

        g.apply(PmGuard.Level.OK, null, 1100);
        verify(redis).delete(PmGuard.KEY);
        assertEquals("OK", g.status().get("level"));
    }

    @Test
    void ipChangeNotifiesOnceWithNewIp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        java.util.Map<String, String> kv = new java.util.HashMap<>();
        when(ops.get(anyString())).thenAnswer(i -> kv.get(i.getArgument(0, String.class)));
        doAnswer(i -> { kv.put(i.getArgument(0), i.getArgument(1)); return null; }).when(ops).set(anyString(), anyString());
        NotifyClient notify = mock(NotifyClient.class);
        IpWatch w = new IpWatch(redis, notify);
        String[] ip = {"1.2.3.4"};
        w.fetcher = url -> url.contains("api64") ? ip[0] : ip[0];   // 沒有 IPv6
        w.check();
        assertEquals("1.2.3.4", w.current());
        verify(notify, never()).send(anyMap());
        w.check();
        verify(notify, never()).send(anyMap());
        ip[0] = "5.6.7.8";
        w.check();
        verify(notify, times(1)).send(argThat((Map<String, String> m) -> m.get("text").contains("1.2.3.4") && m.get("text").contains("5.6.7.8")));
        w.fetcher = url -> null;   // 斷網：不動
        w.check();
        assertEquals("5.6.7.8", w.current());
    }

    @Test
    void cleanRejectsGarbage() {
        assertEquals("2001:db8::1", IpWatch.clean(" 2001:db8::1\n"));
        assertNull(IpWatch.clean("<html>error</html>"));
    }
}
