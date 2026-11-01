package com.quant.notification.service;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Kafka consumer lag 監控（後續 7）：每 30 秒用 AdminClient 比對各 consumer group 已提交 offset 與分區末端。
 * account-service / strategy-service 落後 = 成交晚記、策略晚看到 K 線。連續兩次超過門檻才告警（經 AlertGate 去重）。
 */
@Service
public class KafkaLagMonitor {

    private static final Logger log = LoggerFactory.getLogger(KafkaLagMonitor.class);
    /** 要盯的 group → 說明 */
    static final Map<String, String> GROUPS = new LinkedHashMap<>();
    static {
        GROUPS.put("account-service", "持倉 / 現金帳（order.events）");
        GROUPS.put("order-execution-db", "訂單 DB 回寫（order.events）");
        GROUPS.put("strategy-service", "策略 K 線（market.kline）");
        GROUPS.put("strategy-service-orders", "策略倉位事件（order.events）");
        GROUPS.put("notification-service", "通知（order.events / risk.alerts）");
    }

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}") private String bootstrap;
    @Value("${notification.kafka-lag.threshold:500}") private long threshold;
    @Value("${notification.kafka-lag.enabled:true}") private boolean enabled;

    private final AlertGate gate;
    private volatile Map<String, Object> last = Map.of("checkedAt", 0L, "groups", List.of(), "error", "尚未檢查");
    private final Map<String, Integer> breaches = new HashMap<>();
    private AdminClient admin;

    public KafkaLagMonitor(AlertGate gate) { this.gate = gate; }

    private synchronized AdminClient admin() {
        if (admin == null) {
            Properties p = new Properties();
            p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
            p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "8000");
            admin = AdminClient.create(p);
        }
        return admin;
    }

    @Scheduled(initialDelay = 45_000, fixedDelay = 30_000)
    public void check() {
        if (!enabled) return;
        try { last = measure(); }
        catch (Exception e) {
            log.warn("Kafka lag 檢查失敗: {}", e.getMessage());
            last = Map.of("checkedAt", System.currentTimeMillis(), "groups", List.of(), "error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    Map<String, Object> measure() throws Exception {
        AdminClient a = admin();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, String> g : GROUPS.entrySet()) {
            String group = g.getKey();
            Map<TopicPartition, OffsetAndMetadata> committed;
            try { committed = a.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(8, TimeUnit.SECONDS); }
            catch (Exception e) { rows.add(row(group, g.getValue(), -1, Map.of(), "讀不到 offset: " + e.getMessage())); continue; }
            if (committed.isEmpty()) { rows.add(row(group, g.getValue(), 0, Map.of(), "尚未提交 offset（沒消費過或剛起）")); continue; }
            Map<TopicPartition, OffsetSpec> spec = new HashMap<>();
            committed.keySet().forEach(tp -> spec.put(tp, OffsetSpec.latest()));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = a.listOffsets(spec).all().get(8, TimeUnit.SECONDS);
            long total = 0; Map<String, Long> perTopic = new TreeMap<>();
            for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
                long end = ends.containsKey(e.getKey()) ? ends.get(e.getKey()).offset() : e.getValue().offset();
                long lag = Math.max(0, end - e.getValue().offset());
                total += lag; perTopic.merge(e.getKey().topic(), lag, Long::sum);
            }
            rows.add(row(group, g.getValue(), total, perTopic, null));
            // 連續兩次超標才告警
            int n = total > threshold ? breaches.merge(group, 1, Integer::sum) : breaches.merge(group, 0, (o, z) -> 0);
            if (n == 2) gate.publish(":hourglass_flowing_sand: Kafka 消費落後：" + group + "（" + g.getValue() + "）lag=" + total
                    + " > " + threshold + "，成交 / 行情會晚處理，請看該服務 log", "Kafka lag");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkedAt", System.currentTimeMillis()); out.put("threshold", threshold); out.put("groups", rows);
        return out;
    }

    private static Map<String, Object> row(String group, String desc, long lag, Map<String, Long> perTopic, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("group", group); m.put("desc", desc); m.put("lag", lag); m.put("perTopic", perTopic);
        if (note != null) m.put("note", note);
        return m;
    }

    public Map<String, Object> status() { return last; }
}
