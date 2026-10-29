package com.quant.strategy.service;

import com.quant.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.Map;

/** 讀 OMS 的策略部位快照（啟動對帳）。 */
@FeignClient(name = "order-execution-service", path = "/api/order", contextId = "omsQueryClient")
public interface OmsClient {
    @GetMapping("/orders/strategy-positions")
    ApiResponse<List<Map<String, Object>>> strategyPositions();
}
