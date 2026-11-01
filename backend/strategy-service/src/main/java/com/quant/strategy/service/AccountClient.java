package com.quant.strategy.service;

import com.quant.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

/** 讀目前權益（資金配置守門用）。 */
@FeignClient(name = "account-service", path = "/api/account", contextId = "strategyAccountClient")
public interface AccountClient {
    @GetMapping("/balance")
    ApiResponse<Map<String, Object>> balance();
}
