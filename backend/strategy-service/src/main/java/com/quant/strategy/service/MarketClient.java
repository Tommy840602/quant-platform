package com.quant.strategy.service;

import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.Kline;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/** market-data-service：歷史 K 線（機器人上線 / 改參數時暖機用，不必等真實 K 線慢慢累積）。 */
@FeignClient(name = "market-data-service", path = "/api/market", contextId = "strategyMarketClient")
public interface MarketClient {
    @GetMapping("/klines")
    ApiResponse<List<Kline>> klines(@RequestParam("symbol") String symbol, @RequestParam("interval") String interval,
                                    @RequestParam("limit") int limit);
}
