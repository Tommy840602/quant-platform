package com.quant.order.service;

import com.quant.common.dto.OrderIntent;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** 同步呼叫風控服務取得下單核可（見架構文件 §10.1 事中風控）。 */
@FeignClient(name = "risk-service", path = "/api/risk", contextId = "riskClient")
public interface RiskClient {
    @PostMapping("/check")
    RiskDecision check(@RequestBody OrderIntent intent);

    record RiskDecision(boolean approved, String reason, Double adjustedQty) {}
}
