package com.quant.order.service;

import com.quant.common.enums.OrderStatus;
import org.junit.jupiter.api.Test;

import static com.quant.common.enums.OrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderStateMachineTest {
    @Test
    void filledIsTerminal() {
        for (OrderStatus s : OrderStatus.values()) assertFalse(OrderStateMachine.canTransit(FILLED, s), "FILLED -> " + s);
    }

    @Test
    void unknownCanResolveToAnyExchangeState() {
        assertTrue(OrderStateMachine.canTransit(UNKNOWN, FILLED));
        assertTrue(OrderStateMachine.canTransit(UNKNOWN, REJECTED));
        assertTrue(OrderStateMachine.canTransit(UNKNOWN, SUBMITTED));
        assertTrue(OrderStateMachine.canTransit(SUBMITTED, UNKNOWN));
        assertTrue(OrderStateMachine.canTransit(RISK_APPROVED, UNKNOWN));
    }

    @Test
    void lateSubmittedCannotRegressAFill() {
        assertFalse(OrderStateMachine.canTransit(FILLED, SUBMITTED));
        assertFalse(OrderStateMachine.canTransit(PARTIALLY_FILLED, SUBMITTED));
        assertFalse(OrderStateMachine.canTransit(CANCELED, SUBMITTED));
    }

    @Test
    void partialFillsCanRepeat() {
        assertTrue(OrderStateMachine.canTransit(PARTIALLY_FILLED, PARTIALLY_FILLED));
        assertTrue(OrderStateMachine.canTransit(PARTIALLY_FILLED, FILLED));
    }
}
