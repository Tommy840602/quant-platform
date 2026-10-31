package com.quant.account.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountModeServiceTest {

    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String, String> ops = mock(ValueOperations.class);
    BinanceAccountService binance = mock(BinanceAccountService.class);
    CashLedger cash = mock(CashLedger.class);
    PositionStore positions = mock(PositionStore.class);
    NotifyClient notify = mock(NotifyClient.class);
    EquityCurveService equity = mock(EquityCurveService.class);
    AccountModeService svc;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        when(binance.env()).thenReturn("testnet");
        svc = new AccountModeService(redis, binance, cash, positions, notify, equity);
    }

    @Test
    void defaultIsPaperWhenNothingStored() {
        when(ops.get("account:mode")).thenReturn(null);
        assertEquals("paper", svc.current());
        assertTrue((Boolean) svc.status().get("paper"));
    }

    @Test
    void cannotSwitchToRealWithoutKeys() {
        when(ops.get("account:mode")).thenReturn("paper");
        when(binance.live()).thenReturn(false);
        var ex = assertThrows(IllegalStateException.class, () -> svc.switchTo("main"));
        assertTrue(ex.getMessage().contains("金鑰"));
        verify(ops, never()).set(eq("account:mode"), anyString());
    }

    @Test
    void switchingToRealRebasesDrawdownPeakAndNotifies() {
        when(ops.get("account:mode")).thenReturn("paper", "main");
        when(binance.live()).thenReturn(true);
        var st = svc.switchTo("main");
        verify(ops).set("account:mode", "main");
        verify(equity).rebasePeak();
        verify(notify).send(anyMap());
        assertEquals("main", st.get("mode"));
    }

    @Test
    void resetPaperClearsCashAndPositionsOnly() {
        when(positions.clear("paper")).thenReturn(3);
        when(cash.get("paper")).thenReturn(10000.0);
        var r = svc.resetPaper();
        verify(cash).reset("paper");
        verify(positions, never()).clear("main");
        assertEquals(3, r.get("positionsCleared"));
    }
}
