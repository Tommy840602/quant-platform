package com.quant.order.service;

import com.quant.common.enums.OrderStatus;

import java.util.EnumSet;
import java.util.Map;

/** 訂單狀態機（見架構文件 §9.1）：定義合法狀態轉移，杜絕對終結訂單的無效操作。 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, EnumSet<OrderStatus>> TRANSITIONS = Map.of(
            OrderStatus.CREATED,          EnumSet.of(OrderStatus.RISK_APPROVED, OrderStatus.REJECTED),
            OrderStatus.RISK_APPROVED,    EnumSet.of(OrderStatus.SUBMITTED, OrderStatus.REJECTED, OrderStatus.UNKNOWN),
            OrderStatus.SUBMITTED,        EnumSet.of(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED,
                                                     OrderStatus.CANCELED, OrderStatus.REJECTED, OrderStatus.EXPIRED,
                                                     OrderStatus.UNKNOWN),
            OrderStatus.UNKNOWN,          EnumSet.of(OrderStatus.SUBMITTED, OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED,
                                                     OrderStatus.CANCELED, OrderStatus.REJECTED, OrderStatus.EXPIRED),
            OrderStatus.PARTIALLY_FILLED, EnumSet.of(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED,
                                                     OrderStatus.CANCELED, OrderStatus.EXPIRED),
            OrderStatus.FILLED,           EnumSet.noneOf(OrderStatus.class),
            OrderStatus.CANCELED,         EnumSet.noneOf(OrderStatus.class),
            OrderStatus.REJECTED,         EnumSet.noneOf(OrderStatus.class),
            OrderStatus.EXPIRED,          EnumSet.noneOf(OrderStatus.class)
    );

    private OrderStateMachine() {}

    public static boolean canTransit(OrderStatus from, OrderStatus to) {
        return TRANSITIONS.getOrDefault(from, EnumSet.noneOf(OrderStatus.class)).contains(to);
    }

    public static void assertTransit(OrderStatus from, OrderStatus to) {
        if (!canTransit(from, to)) {
            throw new IllegalStateException("非法狀態轉移: " + from + " -> " + to);
        }
    }
}
