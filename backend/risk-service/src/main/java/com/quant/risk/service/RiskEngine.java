package com.quant.risk.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 風控規則引擎（見架構文件 §10.2）。OMS 每筆單送交易所前都來這裡問一次。
 * 規則（依序）：
 *  1. 全域熔斷
 *  2. 數量合法 / 單筆數量上限
 *  3. 限價偏離保護（限價與現價差 > N% = 胖手指）
 *  4. 每日下單次數
 *  5. 每日名目總額
 *  6. 日虧損鎖（權益較日初跌 ≥ N% → 停止 BUY，SELL 減倉放行）
 *  7. 集中度（BUY 後非穩定幣市值 / 權益 ≤ N%）
 * 6、7 需要帳戶權益；拿不到時 fail-closed 擋 BUY（可關）。
 * 計數器（次數 / 名目 / 日初權益）存 Redis，以台北日為界、48h 過期。
 */
@Service
public class RiskEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);
    static final ZoneId TZ = ZoneId.of("Asia/Taipei");
    static final Set<String> STABLES = Set.of("USDT", "USDC", "FDUSD", "TUSD", "BUSD", "DAI", "USD");

    private final KillSwitch killSwitch;
    private final RiskLimits limits;
    private final StringRedisTemplate redis;
    private final AccountClient account;
    private final java.util.function.Supplier<LocalDate> today;

    @org.springframework.beans.factory.annotation.Autowired
    public RiskEngine(KillSwitch killSwitch, RiskLimits limits, StringRedisTemplate redis, AccountClient account) {
        this(killSwitch, limits, redis, account, () -> LocalDate.now(TZ));
    }

    RiskEngine(KillSwitch killSwitch, RiskLimits limits, StringRedisTemplate redis, AccountClient account,
               java.util.function.Supplier<LocalDate> today) {
        this.killSwitch = killSwitch; this.limits = limits; this.redis = redis; this.account = account; this.today = today;
    }

    public RiskDecision evaluate(Map<String, Object> intent) {
        if (killSwitch.isOn())
            return reject("熔斷已啟動: " + killSwitch.reason());

        double qty = num(intent.get("quantity"));
        if (qty <= 0) return reject("數量不合法");
        double maxQty = limits.maxOrderQty();
        if (maxQty > 0 && qty > maxQty) return new RiskDecision(false, "超過單筆數量上限 " + maxQty, maxQty);

        String symbol = String.valueOf(intent.getOrDefault("symbol", ""));
        String side = String.valueOf(intent.getOrDefault("side", "BUY")).toUpperCase();
        String acct = acct(intent.get("account"));
        boolean buy = "BUY".equals(side);
        double limitPx = num(intent.get("price"));
        double ref = lastPrice(symbol);
        double px = limitPx > 0 ? limitPx : ref;

        // 3. 限價偏離
        double dev = limits.maxPriceDeviationPct();
        if (limitPx > 0 && ref > 0 && dev > 0) {
            double d = Math.abs(limitPx - ref) / ref;
            if (d > dev) return reject(String.format("限價 %.8g 偏離現價 %.8g 達 %.1f%%（上限 %.0f%%），疑似胖手指",
                    limitPx, ref, d * 100, dev * 100));
        }

        // 4. 每日次數
        int maxOrders = limits.maxDailyOrders();
        long ordersToday = counter("orders", acct);
        if (maxOrders > 0 && ordersToday >= maxOrders)
            return reject("今日下單次數已達上限 " + maxOrders + "（" + acct + "）");

        // 5. 每日名目
        double notional = px > 0 ? qty * px : 0;
        double maxNotional = limits.maxDailyNotional();
        double notionalToday = counterD("notional", acct);
        if (maxNotional > 0 && notional > 0 && notionalToday + notional > maxNotional)
            return reject(String.format("今日名目總額 %.0f + 本單 %.0f 超過上限 %.0f USDT", notionalToday, notional, maxNotional));

        // 6/7 需要權益（只有 BUY 才查，SELL 減倉不擋）
        double maxLoss = limits.maxDailyLossPct(), maxPos = limits.maxPositionPct();
        if (buy && (maxLoss > 0 || maxPos > 0)) {
            Equity eq = equity();
            if (eq == null) {
                if (limits.failClosed()) return reject("取不到帳戶權益（account-service），依 fail-closed 拒絕買入");
                log.warn("取不到帳戶權益，fail-closed=false 放行 {}", symbol);
            } else {
                // 6. 日虧損鎖
                if (maxLoss > 0 && eq.total > 0) {
                    double dayStart = dayStartEquity(acct, eq.total);
                    if (dayStart > 0) {
                        double dd = 1 - eq.total / dayStart;
                        if (dd >= maxLoss)
                            return reject(String.format("今日虧損 %.1f%% 已達上限 %.1f%%（日初權益 %.0f → %.0f），停止買入；賣出減倉不受限",
                                    dd * 100, maxLoss * 100, dayStart, eq.total));
                    }
                }
                // 7. 集中度
                if (maxPos > 0 && eq.total > 0 && notional > 0) {
                    double after = (eq.nonStable + notional) / eq.total;
                    if (after > maxPos)
                        return reject(String.format("買入後持倉佔比 %.0f%% 超過上限 %.0f%%（目前 %.0f%%）",
                                after * 100, maxPos * 100, eq.nonStable / eq.total * 100));
                }
            }
        }

        // 核可：計數（過半天才真正送出的也算，寧可多算）
        incr("orders", acct, 1);
        if (notional > 0) incrD("notional", acct, notional);
        return new RiskDecision(true, "ok", null);
    }

    // ── 狀態查詢（風控頁顯示用）──
    public Map<String, Object> status(String accountName) {
        String acct = acct(accountName);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account", acct); m.put("tradingDay", today.get().toString());
        m.put("ordersToday", counter("orders", acct));
        m.put("notionalToday", round2(counterD("notional", acct)));
        Equity eq = equity();
        m.put("equity", eq == null ? null : round2(eq.total));
        m.put("nonStableValue", eq == null ? null : round2(eq.nonStable));
        m.put("positionPct", eq == null || eq.total <= 0 ? null : round4(eq.nonStable / eq.total));
        double ds = eq == null ? 0 : dayStartEquity(acct, eq.total);
        m.put("dayStartEquity", ds <= 0 ? null : round2(ds));
        m.put("dayPnlPct", eq == null || ds <= 0 ? null : round4(eq.total / ds - 1));
        m.put("limits", limits.snapshot());
        m.put("killSwitch", killSwitch.isOn());
        return m;
    }

    /** 重置今日計數與日初權益（手動解鎖用）。 */
    public void resetDay(String accountName) {
        String acct = acct(accountName);
        String d = today.get().toString();
        for (String k : List.of("orders", "notional", "daystart")) {
            try { redis.delete("risk:" + k + ":" + acct + ":" + d); } catch (Exception ignore) {}
        }
        log.warn("風控日計數重置：{}", acct);
    }

    // ── helpers ──
    record Equity(double total, double nonStable) {}

    /** 權益與非穩定幣市值；拿不到回 null。account-service 的 /balance 已含各資產 valueUsdt。 */
    Equity equity() {
        if (account == null) return null;
        try {
            Map<String, Object> bal = account.balance().data();
            if (bal == null || !(bal.get("totalUsdt") instanceof Number t)) return null;
            double nonStable = 0;
            Object list = bal.get("balances");
            if (list instanceof List<?> rows) {
                for (Object o : rows) {
                    if (!(o instanceof Map<?, ?> r)) continue;
                    String asset = String.valueOf(r.get("asset")).toUpperCase();
                    if (STABLES.contains(asset)) continue;
                    if (r.get("valueUsdt") instanceof Number v) nonStable += v.doubleValue();
                }
            }
            return new Equity(t.doubleValue(), nonStable);
        } catch (Exception e) {
            log.warn("查帳戶權益失敗: {}", e.getMessage());
            return null;
        }
    }

    private double dayStartEquity(String acct, double current) {
        String key = "risk:daystart:" + acct + ":" + today.get();
        try {
            String v = redis.opsForValue().get(key);
            if (v != null) return Double.parseDouble(v);
            redis.opsForValue().set(key, String.valueOf(current), 48, TimeUnit.HOURS);
            return current;
        } catch (Exception e) { return 0; }
    }

    private long counter(String name, String acct) {
        try { String v = redis.opsForValue().get("risk:" + name + ":" + acct + ":" + today.get()); return v == null ? 0 : (long) Double.parseDouble(v); }
        catch (Exception e) { return 0; }
    }
    private double counterD(String name, String acct) {
        try { String v = redis.opsForValue().get("risk:" + name + ":" + acct + ":" + today.get()); return v == null ? 0 : Double.parseDouble(v); }
        catch (Exception e) { return 0; }
    }
    private void incr(String name, String acct, long by) {
        String key = "risk:" + name + ":" + acct + ":" + today.get();
        try { redis.opsForValue().increment(key, by); redis.expire(key, 48, TimeUnit.HOURS); } catch (Exception ignore) {}
    }
    private void incrD(String name, String acct, double by) {
        String key = "risk:" + name + ":" + acct + ":" + today.get();
        try { redis.opsForValue().increment(key, by); redis.expire(key, 48, TimeUnit.HOURS); } catch (Exception ignore) {}
    }
    private double lastPrice(String symbol) {
        try { String v = redis.opsForValue().get("price:" + symbol); return v == null ? 0 : Double.parseDouble(v); }
        catch (Exception e) { return 0; }
    }
    private static String acct(Object a) { String s = a == null ? "" : String.valueOf(a); return s.isBlank() ? "main" : s; }
    private static double num(Object o) { try { return o == null ? 0 : Double.parseDouble(o.toString()); } catch (Exception e) { return 0; } }
    private static RiskDecision reject(String why) { return new RiskDecision(false, why, null); }
    private static double round2(double d) { return Math.round(d * 100.0) / 100.0; }
    private static double round4(double d) { return Math.round(d * 10000.0) / 10000.0; }

    public record RiskDecision(boolean approved, String reason, Double adjustedQty) {}
}
