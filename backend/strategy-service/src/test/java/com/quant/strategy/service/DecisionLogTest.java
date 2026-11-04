package com.quant.strategy.service;

import com.quant.common.enums.OrderSide;
import com.quant.strategy.position.StrategyPositionBook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DecisionLogTest {

    @Test
    @SuppressWarnings("unchecked")
    void dedupesAndBatches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BotRiskManager risk = new BotRiskManager(new StrategyPositionBook(), mock(NotifyClient.class));
        DecisionLog d = new DecisionLog(jdbc, risk);
        d.record("b", "X", OrderSide.BUY, 1, "盤整盤（ADX 14），趨勢策略不進場");
        d.record("b", "X", OrderSide.BUY, 1, "盤整盤（ADX 14），趨勢策略不進場");   // 55 秒內重複
        d.record("b", "X", OrderSide.BUY, 1, "已送單 0.5");
        d.record("b", "X", OrderSide.BUY, 1, "排名中…");                          // 過渡狀態不記
        d.flush();
        ArgumentCaptor<List<Object[]>> cap = ArgumentCaptor.forClass(List.class);
        verify(jdbc).batchUpdate(anyString(), cap.capture());
        assertEquals(2, cap.getValue().size());
        assertEquals(false, cap.getValue().get(0)[5]);
        assertEquals(true, cap.getValue().get(1)[5], "已送單 = 有動作");
    }

    @Test
    void actedClassification() {
        assertTrue(DecisionLog.acted("停損出場 · 已送單 0.37"));
        assertTrue(DecisionLog.acted("[模擬] 模擬成交 0.6 @10"));
        assertFalse(DecisionLog.acted("被拒：NOTIONAL"));
        assertFalse(DecisionLog.acted("冷卻中（出場後 5 根內不進場）"));
    }
}
