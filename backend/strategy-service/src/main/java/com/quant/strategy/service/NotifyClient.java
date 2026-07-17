package com.quant.strategy.service;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/** 送 Slack 通知（見架構文件 §3.2）。 */
@FeignClient(name = "notification-service", path = "/api/notify", contextId = "stratNotifyClient")
public interface NotifyClient {
    @PostMapping("/test")
    Object send(@RequestBody Map<String, String> body);
}
