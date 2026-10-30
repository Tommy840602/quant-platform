package com.quant.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User Data Stream 連線狀態的單一真相來源。
 * 每條串流（SPOT / USDT_M）啟動時 {@link #expect} 登記「應該要連上」，連上/斷線時回報；
 * OrderService 送單後以 {@link #isConnected} 決定成交回報靠串流還是靠下單回應解析。
 * 看門狗：應連而未連超過 60 秒 → Slack 告警（10 分鐘一次），避免「UDS 開了但沒跑」靜默吞掉成交。
 */
@Service
public class UdsRegistry {

    private static final Logger log = LoggerFactory.getLogger(UdsRegistry.class);
    public static final String SPOT = "SPOT", FUTURES = "USDT_M";

    static final class State {
        volatile boolean expected;            // 設定要求啟用且非 dry-run
        volatile boolean connected;
        volatile long connectedAt, disconnectedAt = System.currentTimeMillis(), lastEventAt;
        volatile String lastError = "";
        volatile long events;
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final NotifyClient notify;
    private volatile long lastAlert = 0;

    public UdsRegistry(NotifyClient notify) { this.notify = notify; }

    private State st(String market) { return states.computeIfAbsent(market, k -> new State()); }

    public void expect(String market) { st(market).expected = true; }

    public void markConnected(String market) {
        State s = st(market);
        s.connected = true; s.connectedAt = System.currentTimeMillis(); s.lastError = "";
        log.info("UDS[{}] 已連線並訂閱", market);
    }

    public void markDisconnected(String market, String reason) {
        State s = st(market);
        boolean was = s.connected;
        s.connected = false; s.disconnectedAt = System.currentTimeMillis(); s.lastError = reason == null ? "" : reason;
        if (was) log.warn("UDS[{}] 斷線: {}", market, reason);
    }

    public void onEvent(String market) { State s = st(market); s.lastEventAt = System.currentTimeMillis(); s.events++; }

    /** 送單當下串流是否活著。沒登記過的市場一律 false → 呼叫端退回解析下單回應。 */
    public boolean isConnected(String market) {
        State s = states.get(market);
        return s != null && s.connected;
    }

    public boolean isExpected(String market) {
        State s = states.get(market);
        return s != null && s.expected;
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String m : new String[]{SPOT, FUTURES}) {
            State s = states.get(m);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("expected", s != null && s.expected);
            row.put("connected", s != null && s.connected);
            row.put("connectedAt", s == null ? 0 : s.connectedAt);
            row.put("disconnectedAt", s == null ? 0 : s.disconnectedAt);
            row.put("lastEventAt", s == null ? 0 : s.lastEventAt);
            row.put("events", s == null ? 0 : s.events);
            row.put("lastError", s == null ? "" : s.lastError);
            out.put(m, row);
        }
        return out;
    }

    /** 看門狗：應連未連 > 60s 即告警（限流 10 分鐘）。 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 30_000)
    public void watchdog() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, State> e : states.entrySet()) {
            State s = e.getValue();
            if (s.expected && !s.connected && now - s.disconnectedAt > 60_000)
                sb.append(e.getKey()).append("（").append(s.lastError.isBlank() ? "未連線" : s.lastError).append("）");
        }
        if (sb.length() == 0 || now - lastAlert < 600_000) return;
        lastAlert = now;
        String text = ":rotating_light: User Data Stream 未連線 " + sb
                + " ｜ 已退回「下單回應解析」，掛單後才成交的單會等對帳排程補記；請檢查 Ed25519/合約金鑰與網路";
        log.error(text);
        try { notify.send(Map.of("text", text)); } catch (Exception ignore) {}
    }
}
