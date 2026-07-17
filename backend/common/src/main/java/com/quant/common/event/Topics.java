package com.quant.common.event;

/** Kafka 主題常數。 */
public final class Topics {
    private Topics() {}
    public static final String MARKET_KLINE   = "market.kline";
    public static final String MARKET_DEPTH   = "market.depth";
    public static final String ORDER_INTENT   = "order.intent";
    public static final String ORDER_EVENTS   = "order.events";
    public static final String RISK_ALERTS    = "risk.alerts";
}
