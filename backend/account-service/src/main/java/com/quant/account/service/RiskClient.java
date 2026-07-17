package com.quant.account.service;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/** 呼叫風控熔斷（見架構文件 §10.3）。 */
@FeignClient(name = "risk-service", path = "/api/risk", contextId = "acctRiskClient")
public interface RiskClient {
    @PostMapping("/kill-switch")
    Map<String, Object> killSwitch(@RequestParam boolean on, @RequestParam String reason);
}
