package com.quant.risk.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KillSwitchTest {

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate redisWith(ValueOperations<String, String> ops) {
        StringRedisTemplate r = mock(StringRedisTemplate.class);
        when(r.opsForValue()).thenReturn(ops);
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void restoresOnStateFromRedis() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(ops.get(KillSwitch.K_ON)).thenReturn("1");
        when(ops.get(KillSwitch.K_REASON)).thenReturn("drawdown");
        KillSwitch ks = new KillSwitch(redisWith(ops), false);
        assertTrue(ks.isOn(), "重啟後仍熔斷");
        assertEquals("drawdown", ks.reason());
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsClosedWhenRedisUnavailable() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(ops.get(anyString())).thenThrow(new RuntimeException("redis down"));
        KillSwitch ks = new KillSwitch(redisWith(ops), false);
        assertTrue(ks.isOn(), "Redis 失聯 → 擋單");
        assertFalse(ks.redisHealthy());
    }

    @Test
    @SuppressWarnings("unchecked")
    void setPersists() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(ops.get(KillSwitch.K_ON)).thenReturn("0");
        KillSwitch ks = new KillSwitch(redisWith(ops), false);
        ks.set(true, "manual");
        verify(ops).set(KillSwitch.K_ON, "1");
        verify(ops).set(KillSwitch.K_REASON, "manual");
    }
}
