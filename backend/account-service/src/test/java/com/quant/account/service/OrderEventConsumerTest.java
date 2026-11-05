package com.quant.account.service;

import com.quant.account.entity.PositionEntity;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderStatus;
import com.quant.common.event.OrderEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderEventConsumerTest {

    static OrderEvent ev(String id, String market, String mode) {
        return new OrderEvent("c1", "x", "BTCUSDT", OrderSide.BUY, OrderStatus.FILLED, 0.01, 50000, 0, "main", id,
                "mainnet", market, mode);
    }

    @Test
    void duplicateEventIsSkippedByDbClaim() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("INSERT INTO processed_events"), any(), any())).thenReturn(1, 0);
        PositionStore pos = mock(PositionStore.class);
        when(pos.onFilled(any(), any(), any(), any(), any(), any(), anyDouble(), anyDouble())).thenReturn(new PositionEntity());
        CashLedger cash = mock(CashLedger.class);
        OrderEventConsumer c = new OrderEventConsumer(pos, cash, jdbc);
        c.onOrderEvent(ev("e1", "SPOT", "LIVE"));
        c.onOrderEvent(ev("e1", "SPOT", "LIVE"));
        verify(pos, times(1)).onFilled(eq("main"), eq("mainnet"), eq("LIVE"), eq("SPOT"), eq("BTCUSDT"), eq(OrderSide.BUY), eq(0.01), eq(50000.0));
        verify(cash, times(1)).onFill(eq("main"), eq("mainnet"), eq("LIVE"), eq(OrderSide.BUY), eq(0.01), eq(50000.0), eq("e1"));
    }

    @Test
    void futuresFillTouchesOnlyFuturesPositionNotCash() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("INSERT INTO processed_events"), any(), any())).thenReturn(1);
        PositionStore pos = mock(PositionStore.class);
        when(pos.onFilled(any(), any(), any(), any(), any(), any(), anyDouble(), anyDouble())).thenReturn(new PositionEntity());
        CashLedger cash = mock(CashLedger.class);
        new OrderEventConsumer(pos, cash, jdbc).onOrderEvent(ev("e2", "USDT_M", "LIVE"));
        verify(pos).onFilled(any(), eq("mainnet"), eq("LIVE"), eq("USDT_M"), any(), any(), anyDouble(), anyDouble());
        verifyNoInteractions(cash);
    }

    @Test
    void dbFailurePropagatesSoKafkaRedelivers() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("INSERT INTO processed_events"), any(), any())).thenThrow(new RuntimeException("db down"));
        OrderEventConsumer c = new OrderEventConsumer(mock(PositionStore.class), mock(CashLedger.class), jdbc);
        assertThrows(RuntimeException.class, () -> c.onOrderEvent(ev("e3", "SPOT", "DRY")), "不可吞掉：要讓 transaction 回滾、Kafka 重送");
    }
}
