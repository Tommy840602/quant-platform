package com.quant.strategy.service;

import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.OrderIntent;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/** 呼叫 Order/Execution Service 送出下單意圖（見架構文件 §9）。 */
@FeignClient(name = "order-execution-service", path = "/api/order", contextId = "orderClient")
public interface OrderClient {
    @PostMapping("/orders")
    ApiResponse<String> submit(@RequestBody OrderIntent intent);

    @PostMapping("/orders/leverage")
    ApiResponse<String> leverage(@RequestParam("symbol") String symbol, @RequestParam("leverage") int leverage);

    @DeleteMapping("/orders/{clientOrderId}")
    ApiResponse<String> cancel(@PathVariable("clientOrderId") String clientOrderId, @RequestParam("symbol") String symbol,
                               @RequestParam("market") String market);
}
