package com.quant.notification.controller;

import com.quant.common.dto.ApiResponse;
import com.quant.notification.service.SlackNotifier;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 通知 API（見架構文件 §3.2）。 */
@RestController
@RequestMapping("/")
public class NotificationController {

    private final SlackNotifier slack;
    public NotificationController(SlackNotifier slack) { this.slack = slack; }

    /** 測試發送：POST /api/notify/test  body: {text} */
    @PostMapping("/test")
    public ApiResponse<String> test(@RequestBody Map<String, String> body) {
        slack.send(body.getOrDefault("text", "測試訊息 from 量化平台"));
        return ApiResponse.ok("sent");
    }
}
