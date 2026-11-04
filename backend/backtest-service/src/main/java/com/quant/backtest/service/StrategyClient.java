package com.quant.backtest.service;

import com.quant.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/** strategy-service：各標的實測滑價（影子雙胞胎記錄的「送單參考價 vs 成交價」）。 */
@FeignClient(name = "strategy-service", path = "/api/strategy", contextId = "backtestStrategyClient")
public interface StrategyClient {
    @GetMapping("/strategies/slippage")
    ApiResponse<Map<String, Map<String, Object>>> slippage(@RequestParam("days") int days);
}
