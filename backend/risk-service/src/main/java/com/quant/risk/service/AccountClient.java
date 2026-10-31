package com.quant.risk.service;

import com.quant.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

/** 讀帳戶權益 / 資產（集中度、日虧損用）。 */
@FeignClient(name = "account-service", path = "/api/account", contextId = "riskAccountClient")
public interface AccountClient {
    @GetMapping("/balance")
    ApiResponse<Map<String, Object>> balance();
}
