package com.quant.gateway.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 看門狗：每 30 秒檢查一次，異常持續 2 分鐘以上發 Slack，恢復也通知。
 * - 任一後端服務 DOWN / 連不到
 * - 行情超過 2 分鐘沒進來（Binance WebSocket 斷線、market-data 卡住）
 * 直接打 Slack webhook，不經 notification-service（它自己掛了也要能叫）。
 * 另外可設 HEARTBEAT_URL（例：healthchecks.io）每分鐘 ping 一次：Mac 睡著 / 整台掛掉時由外部服務通知你。
 */
@Configuration
@EnableScheduling
class WatchdogConfig {}

@Component
public class Watchdog {
    private static final Logger log = LoggerFactory.getLogger(Watchdog.class);
    static final long GRACE_MS = 120_000;
    static final long MARKET_STALE_SEC = 120;

    private final SystemStatusController status;
    private final AlertTracker tracker = new AlertTracker(GRACE_MS);
    private final WebClient http = WebClient.create();   // 外部網址：不能用 @LoadBalanced 那個
    private final String slackUrl, heartbeatUrl;
    private final boolean enabled;
    private long lastBeat;

    public Watchdog(SystemStatusController status,
                    @Value("${SLACK_WEBHOOK_URL:}") String slackUrl,
                    @Value("${HEARTBEAT_URL:}") String heartbeatUrl,
                    @Value("${WATCHDOG_ENABLED:true}") boolean enabled) {
        this.status = status; this.slackUrl = slackUrl.trim(); this.heartbeatUrl = heartbeatUrl.trim(); this.enabled = enabled;
        if (enabled && this.slackUrl.isEmpty()) log.info("看門狗：未設 SLACK_WEBHOOK_URL，只記 log 不發通知");
    }

    /** 開機 3 分鐘後才開始（其他服務 JVM 啟動 + Nacos 註冊需要時間）。 */
    @Scheduled(initialDelay = 180_000, fixedDelay = 30_000)
    public void tick() {
        if (!enabled) return;
        try {
            List<Map<String, Object>> services = status.probeAll().block(Duration.ofSeconds(15));
            Long age = status.lastTickAgeSec().block(Duration.ofSeconds(5));
            evaluate(services == null ? List.of() : services, age == null ? -1 : age, System.currentTimeMillis()).forEach(this::send);
            beat();
        } catch (Exception e) {
            log.warn("看門狗檢查失敗：{}", e.toString());
        }
    }

    List<String> evaluate(List<Map<String, Object>> services, long tickAgeSec, long now) {
        List<String> msgs = new ArrayList<>();
        for (Map<String, Object> s : services) {
            String name = String.valueOf(s.get("name"));
            boolean bad = !"UP".equals(String.valueOf(s.get("status")));
            String detail = s.get("error") != null ? String.valueOf(s.get("error")) : "狀態 " + s.get("status") + downComponents(s);
            msgs.addAll(tracker.update("svc:" + name, name, bad, detail, now));
        }
        boolean stale = tickAgeSec < 0 || tickAgeSec > MARKET_STALE_SEC;
        String detail = tickAgeSec < 0 ? "一直沒收到行情" : "最後一筆是 " + AlertTracker.fmt(tickAgeSec * 1000) + "前";
        msgs.addAll(tracker.update("market", "行情資料", stale, detail + "（機器人此時不會進出場）", now));
        return msgs;
    }

    private static String downComponents(Map<String, Object> s) {
        if (!(s.get("components") instanceof Map<?, ?> c)) return "";
        List<String> down = new ArrayList<>();
        for (Map.Entry<?, ?> e : c.entrySet()) if (!"UP".equals(String.valueOf(e.getValue()))) down.add(String.valueOf(e.getKey()));
        return down.isEmpty() ? "" : "（" + String.join("、", down) + " 異常）";
    }

    private void send(String text) {
        log.warn("[看門狗] {}", text);
        if (slackUrl.isEmpty()) return;
        http.post().uri(slackUrl).bodyValue(Map.of("text", text)).retrieve().toBodilessEntity()
                .timeout(Duration.ofSeconds(10))
                .subscribe(r -> {}, e -> log.warn("Slack 通知送不出去：{}", e.toString()));
    }

    private void beat() {
        long now = System.currentTimeMillis();
        if (heartbeatUrl.isEmpty() || now - lastBeat < 55_000) return;
        lastBeat = now;
        http.get().uri(heartbeatUrl).retrieve().toBodilessEntity().timeout(Duration.ofSeconds(10))
                .subscribe(r -> {}, e -> log.debug("heartbeat 失敗：{}", e.toString()));
    }
}
