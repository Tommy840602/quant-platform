package com.quant.notification.controller;

import com.quant.common.dto.ApiResponse;
import com.quant.notification.service.AlertGate;
import com.quant.notification.service.DailyDigest;
import com.quant.notification.service.SlackNotifier;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 通知 API（見架構文件 §3.2）。 */
@RestController
@RequestMapping("/")
public class NotificationController {

    private final SlackNotifier slack;
    private final AlertGate gate;
    private final DailyDigest digest;
    public NotificationController(SlackNotifier slack, AlertGate gate, DailyDigest digest) { this.slack = slack; this.gate = gate; this.digest = digest; }

    /**
     * 各服務推告警的入口：POST /api/notify/test  body: {text, category?}
     * 經 AlertGate 去重（同類 10 分鐘一則）；回 sent / suppressed。
     */
    @PostMapping("/test")
    public ApiResponse<String> test(@RequestBody Map<String, String> body) {
        String text = body.getOrDefault("text", "測試訊息 from 量化平台");
        String cat = body.getOrDefault("category", categorize(text));
        return ApiResponse.ok(gate.publish(text, cat) ? "sent" : "suppressed");
    }

    /** 告警統計：GET /api/notify/stats */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() { return ApiResponse.ok(gate.stats()); }

    /** 立即推每日摘要：POST /api/notify/digest/now */
    @PostMapping("/digest/now")
    public ApiResponse<String> digestNow() { return ApiResponse.ok(digest.sendNow()); }

    /** 依 emoji / 關鍵字粗分類（給統計用）。 */
    static String categorize(String t) {
        if (t == null) return "其他";
        if (t.contains(":rotating_light:") || t.contains("熔斷")) return "嚴重告警";
        if (t.contains(":lock:") || t.contains("上膛") || t.contains("Dry-Run")) return "真錢安全鎖";
        if (t.contains("UDS") || t.contains("User Data Stream")) return "成交回報串流";
        if (t.contains("金鑰") || t.contains("IP")) return "金鑰 / IP";
        if (t.contains("下單被拒")) return "拒單";
        if (t.contains("對時") || t.contains("時鐘")) return "對時";
        return "其他";
    }
}
