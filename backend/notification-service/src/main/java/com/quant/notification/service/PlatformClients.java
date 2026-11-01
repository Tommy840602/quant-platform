package com.quant.notification.service;

import com.quant.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/** 每日摘要用：各服務的唯讀狀態。 */
public final class PlatformClients {
    private PlatformClients() {}

    @FeignClient(name = "strategy-service", path = "/api/strategy", contextId = "notifyStrategyClient")
    public interface StrategyClient {
        @GetMapping("/strategies") ApiResponse<List<Map<String, Object>>> list();
    }
    @FeignClient(name = "risk-service", path = "/api/risk", contextId = "notifyRiskClient")
    public interface RiskClient {
        @GetMapping("/status") ApiResponse<Map<String, Object>> status(@RequestParam("account") String account);
    }
    @FeignClient(name = "order-execution-service", path = "/api/order", contextId = "notifyOmsClient")
    public interface OmsClient {
        @GetMapping("/config/uds") ApiResponse<Map<String, Object>> uds();
        @GetMapping("/config") ApiResponse<Map<String, Object>> config();
    }
}
