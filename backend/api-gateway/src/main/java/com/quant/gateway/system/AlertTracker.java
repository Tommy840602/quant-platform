package com.quant.gateway.system;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「異常持續多久才叫」的狀態機（純邏輯、好測）：
 * 連續異常 ≥ graceMs 才發一次警報；之後恢復發一次恢復訊息。抖一下就好的不吵你。
 */
public class AlertTracker {
    private final long graceMs;
    private final Map<String, Long> badSince = new ConcurrentHashMap<>();
    private final Map<String, Boolean> alerted = new ConcurrentHashMap<>();

    public AlertTracker(long graceMs) { this.graceMs = graceMs; }

    /** 回本次要送出的訊息（0 或 1 則）。 */
    public List<String> update(String key, String label, boolean bad, String detail, long now) {
        List<String> out = new ArrayList<>(1);
        if (bad) {
            long since = badSince.computeIfAbsent(key, k -> now);
            if (!alerted.getOrDefault(key, false) && now - since >= graceMs) {
                alerted.put(key, true);
                out.add("🔴 " + label + " 異常已超過 " + fmt(now - since) + (detail == null || detail.isBlank() ? "" : "：" + detail));
            }
        } else {
            Long since = badSince.remove(key);
            if (since != null && Boolean.TRUE.equals(alerted.remove(key)))
                out.add("🟢 " + label + " 已恢復（中斷約 " + fmt(now - since) + "）");
        }
        return out;
    }

    public boolean isAlerting(String key) { return alerted.getOrDefault(key, false); }

    static String fmt(long ms) {
        long s = ms / 1000;
        return s < 60 ? s + " 秒" : s < 3600 ? (s / 60) + " 分鐘" : (s / 3600) + " 小時 " + (s % 3600 / 60) + " 分";
    }
}
