package com.quant.common.event;

import com.quant.common.dto.Kline;

/** 行情事件（Kafka 主題 market.kline）。 */
public record MarketDataEvent(String type, Kline kline, long timestamp) {}
