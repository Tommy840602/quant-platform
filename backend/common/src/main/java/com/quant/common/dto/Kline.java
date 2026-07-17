package com.quant.common.dto;

/** 標準化 K 線資料（跨服務共用）。 */
public record Kline(
        String symbol,
        String interval,
        long openTime,
        double open,
        double high,
        double low,
        double close,
        double volume,
        long closeTime,
        boolean closed
) {}
