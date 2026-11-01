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

    /** 切 env 後重置風控日計數 / 日初權益（testnet 的 39 萬假錢不能當 mainnet 的日初）。 */
    @PostMapping("/status/reset")
    Object resetDay(@RequestParam String account);
}
