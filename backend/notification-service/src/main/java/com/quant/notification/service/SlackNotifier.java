package com.quant.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Slack 通知（見架構文件 §3 Notification Service、§12.2）。
 * 透過 Incoming Webhook 發送訊息；未設定 webhook 時僅記錄 log（方便無 Slack 時開發）。
 */
@Service
public class SlackNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${notification.slack.webhook-url:}")
    private String webhookUrl;

    public void send(String text) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.info("[Slack 未設定，僅記錄] {}", text);
            return;
        }
        try {
            String payload = "{\"text\":" + jsonString(text) + "}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) log.warn("Slack 回應 {}: {}", resp.statusCode(), resp.body());
        } catch (Exception e) { log.warn("Slack 發送失敗: {}", e.getMessage()); }
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
