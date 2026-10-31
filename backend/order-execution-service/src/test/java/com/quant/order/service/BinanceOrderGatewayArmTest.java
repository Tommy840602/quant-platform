package com.quant.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真錢安全鎖：正式網 dryRun=false 還要上膛且未過期才會真送單。 */
class BinanceOrderGatewayArmTest {

    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String, String> ops = mock(ValueOperations.class);
    NotifyClient notify = mock(NotifyClient.class);
    Map<String, String> kv = new HashMap<>();
    BinanceOrderGateway gw;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(i -> kv.get(i.getArgument(0, String.class)));
        doAnswer(i -> { kv.put(i.getArgument(0), i.getArgument(1)); return null; }).when(ops).set(anyString(), anyString());
        when(redis.delete(anyString())).thenAnswer(i -> kv.remove(i.getArgument(0, String.class)) != null);
        gw = new BinanceOrderGateway(notify, mock(SymbolFilterService.class), redis);
        ReflectionTestUtils.setField(gw, "env", "mainnet");
        ReflectionTestUtils.setField(gw, "dryRun", false);
        ReflectionTestUtils.setField(gw, "armTtlHours", 8.0);
        ReflectionTestUtils.setField(gw, "spotUrl", "https://api.binance.com");
        ReflectionTestUtils.setField(gw, "spotKey", "k"); ReflectionTestUtils.setField(gw, "spotSecret", "s");
    }

    @Test
    void mainnetWithoutArmIsStillDryRun() {
        assertTrue(gw.isDryRun(), "dryRun=false 但沒上膛 → 視為 Dry-Run");
    }

    @Test
    void armingEnablesRealOrdersAndNotifies() {
        gw.arm("test");
        assertFalse(gw.isDryRun());
        verify(notify).send(argThat(m -> m.get("text").contains("真實送單已開啟")));
        assertTrue((Boolean) gw.configStatus().get("armed"));
        assertTrue(((Number) gw.configStatus().get("armRemainingSec")).longValue() > 7 * 3600);
    }

    @Test
    void expiredArmLocksBackToDryRunOnce() {
        kv.put("trading:armed-until", String.valueOf(System.currentTimeMillis() - 1000));   // 已過期
        assertTrue(gw.isDryRun());
        gw.checkArmExpiry();
        assertTrue((Boolean) ReflectionTestUtils.getField(gw, "dryRun"), "硬切回 dryRun=true");
        gw.checkArmExpiry();
        verify(notify, times(1)).send(argThat(m -> m.get("text").contains("自動鎖回")));
    }

    @Test
    void testnetNeverNeedsArming() {
        ReflectionTestUtils.setField(gw, "env", "testnet");
        assertFalse(gw.isDryRun());
    }

    @Test
    void redisDownMeansLocked() {
        when(ops.get(anyString())).thenThrow(new RuntimeException("redis down"));
        assertTrue(gw.isDryRun(), "上膛狀態讀不到 → 不送真單");
    }

    @Test
    void switchingBackToDryRunDisarms() {
        gw.arm("test");
        gw.applyConfig(null, true, null, null);
        assertTrue(gw.isDryRun());
        assertNull(kv.get("trading:armed-until"));
    }

    @Test
    void ttlZeroDisablesAutoLockAndIsOverridableAtRuntime() {
        gw.setArmTtlHours(0);                       // 風控頁關閉自動鎖回
        assertEquals(0.0, gw.armTtlHours(), 1e-9);
        gw.arm("test");
        assertFalse(gw.isDryRun());
        assertTrue((Boolean) gw.configStatus().get("armUnlimited"));
        assertEquals(0L, ((Number) gw.configStatus().get("armRemainingSec")).longValue());
        gw.checkArmExpiry();
        assertFalse(gw.isDryRun(), "不限時不會被鎖回");
        verify(notify, never()).send(argThat(m -> m.get("text").contains("自動鎖回 Dry-Run")));

        gw.setArmTtlHours(2);                       // 改回 2 小時：已上膛 → 依新時效重算
        long remain = ((Number) gw.configStatus().get("armRemainingSec")).longValue();
        assertTrue(remain > 7000 && remain <= 7200);
        assertFalse((Boolean) gw.configStatus().get("armUnlimited"));
    }
}
