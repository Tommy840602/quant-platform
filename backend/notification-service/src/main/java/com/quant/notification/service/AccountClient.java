package com.quant.notification.service;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

@FeignClient(name = "account-service", path = "/api/account", contextId = "notifyAccountClient")
public interface AccountClient {
    @PostMapping("/summary/now")
    Object summaryNow();
}
