package com.quant.risk.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 風控門檻：環境變數給預設，風控頁可即時改並存 Redis（risk:limits:*），重啟不丟。
 * 0 = 該規則關閉（不限）。
 */
@Component
public class RiskLimits {

    private static final Logger log = LoggerFactory.getLogger(RiskLimits.class);
    private static final String KEY = "risk:limits:";

    @Value("${risk.max-order-qty:1000000000}") private double maxOrderQty;
    @Value("${risk.max-position-pct:0.5}") private double maxPositionPct;          // 買後非穩定幣市值 / 權益 ≤ 50%
    @Value("${risk.max-daily-loss-pct:0.03}") private double maxDailyLossPct;      // 當日權益較日初跌 ≥ 3% → 停止買入
    @Value("${risk.max-daily-orders:50}") private int maxDailyOrders;              // 每日下單次數
    @Value("${risk.max-daily-notional-usdt:0}") private double maxDailyNotional;   // 每日名目總額（0=不限）
    @Value("${risk.max-price-deviation-pct:0.05}") private double maxPriceDeviationPct;   // 限價偏離現價 > 5% 視為胖手指
    @Value("${risk.fail-closed:true}") private boolean failClosed;                 // 帳戶資料拿不到 → BUY 一律拒

    private final StringRedisTemplate redis;
    public RiskLimits(StringRedisTemplate redis) { this.redis = redis; }

    private double d(String k, double def) {
        try { String v = redis.opsForValue().get(KEY + k); return v == null ? def : Double.parseDouble(v); }
        catch (Exception e) { return def; }
    }
    private boolean b(String k, boolean def) {
        try { String v = redis.opsForValue().get(KEY + k); return v == null ? def : Boolean.parseBoolean(v); }
        catch (Exception e) { return def; }
    }

    public double maxOrderQty()          { return d("maxOrderQty", maxOrderQty); }
    public double maxPositionPct()       { return d("maxPositionPct", maxPositionPct); }
    public double maxDailyLossPct()      { return d("maxDailyLossPct", maxDailyLossPct); }
    public int    maxDailyOrders()       { return (int) d("maxDailyOrders", maxDailyOrders); }
    public double maxDailyNotional()     { return d("maxDailyNotional", maxDailyNotional); }
    public double maxPriceDeviationPct() { return d("maxPriceDeviationPct", maxPriceDeviationPct); }
    public boolean failClosed()          { return b("failClosed", failClosed); }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maxOrderQty", maxOrderQty()); m.put("maxPositionPct", maxPositionPct());
        m.put("maxDailyLossPct", maxDailyLossPct()); m.put("maxDailyOrders", maxDailyOrders());
        m.put("maxDailyNotional", maxDailyNotional()); m.put("maxPriceDeviationPct", maxPriceDeviationPct());
        m.put("failClosed", failClosed());
        return m;
    }

    /** 只更新有給的鍵；比例類夾在 [0,1]。 */
    public Map<String, Object> update(Map<String, Object> patch) {
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            String k = e.getKey(); Object v = e.getValue();
            if (v == null) continue;
            String val;
            switch (k) {
                case "maxPositionPct", "maxDailyLossPct", "maxPriceDeviationPct" -> val = String.valueOf(clamp01(num(v)));
                case "maxOrderQty", "maxDailyNotional" -> val = String.valueOf(Math.max(0, num(v)));
                case "maxDailyOrders" -> val = String.valueOf(Math.max(0, (int) num(v)));
                case "failClosed" -> val = String.valueOf(Boolean.parseBoolean(String.valueOf(v)));
                default -> { continue; }
            }
            redis.opsForValue().set(KEY + k, val);
        }
        log.warn("風控門檻更新：{}", snapshot());
        return snapshot();
    }

    private static double num(Object v) { return Double.parseDouble(String.valueOf(v)); }
    private static double clamp01(double x) { return Math.max(0, Math.min(1, x)); }
}
