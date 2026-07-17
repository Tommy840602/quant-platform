package com.quant.backtest.service;

import com.quant.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

/** 取實盤訂單績效歸因（見架構文件 §7.4 一致性驗證）。 */
@FeignClient(name = "order-execution-service", path = "/api/order", contextId = "liveOrderClient")
public interface LiveOrderClient {
    @GetMapping("/orders/attribution")
    ApiResponse<List<Map<String, Object>>> attribution();
}
