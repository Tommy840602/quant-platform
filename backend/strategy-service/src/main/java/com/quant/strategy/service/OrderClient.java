package com.quant.strategy.service;

import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.OrderIntent;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** 呼叫 Order/Execution Service 送出下單意圖（見架構文件 §9）。 */
@FeignClient(name = "order-execution-service", path = "/api/order", contextId = "orderClient")
public interface OrderClient {
    @PostMapping("/orders")
    ApiResponse<String> submit(@RequestBody OrderIntent intent);
}
