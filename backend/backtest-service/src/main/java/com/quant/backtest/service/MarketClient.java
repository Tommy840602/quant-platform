package com.quant.backtest.service;

import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.Kline;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/** 真實 OHLCV 來源：market-data-service（TimescaleDB 優先，不夠才打幣安 REST）。 */
@FeignClient(name = "market-data-service", path = "/api/market", contextId = "backtestMarketClient")
public interface MarketClient {
    @GetMapping("/klines")
    ApiResponse<List<Kline>> klines(@RequestParam("symbol") String symbol, @RequestParam("interval") String interval,
                                    @RequestParam("limit") int limit);

    @GetMapping("/klines/range")
    ApiResponse<List<Kline>> range(@RequestParam("symbol") String symbol, @RequestParam("interval") String interval,
                                   @RequestParam("from") long from, @RequestParam("to") long to, @RequestParam("limit") int limit);
}
