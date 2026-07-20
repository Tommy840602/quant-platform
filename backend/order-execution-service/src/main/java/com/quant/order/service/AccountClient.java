package com.quant.order.service;

import com.quant.common.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/** 取帳戶可用餘額做下單前餘額檢查（見架構文件 §10）。 */
@FeignClient(name = "account-service", path = "/api/account", contextId = "orderAccountClient")
public interface AccountClient {
    @GetMapping("/balance")
    ApiResponse<Map<String, Object>> balance(@RequestParam(defaultValue = "main") String account);
}
