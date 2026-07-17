package com.quant.account.service;

import com.quant.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

/** 取 order-execution 當日成交統計（見架構文件 §11）。 */
@FeignClient(name = "order-execution-service", path = "/api/order", contextId = "acctOrderStatsClient")
public interface OrderStatsClient {
    @GetMapping("/orders/daily-stats")
    ApiResponse<Map<String, Object>> dailyStats();
}
