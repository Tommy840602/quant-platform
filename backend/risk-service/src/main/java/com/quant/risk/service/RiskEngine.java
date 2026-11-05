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
    private final RiskStore store;
    /** 在途預留視窗：核可後多久內還沒反映在帳戶持倉裡的單，都算進集中度 / 曝險。 */
    static final long PENDING_WINDOW_MS = 60_000;

    @org.springframework.beans.factory.annotation.Autowired
    public RiskEngine(KillSwitch killSwitch, RiskLimits limits, StringRedisTemplate redis, AccountClient account) {
        this(killSwitch, limits, redis, account, () -> LocalDate.now(TZ), new RiskStore.Redis(redis));
    }

    RiskEngine(KillSwitch killSwitch, RiskLimits limits, StringRedisTemplate redis, AccountClient account,
               java.util.function.Supplier<LocalDate> today) {
        this(killSwitch, limits, redis, account, today, new RiskStore.Redis(redis));
    }

    RiskEngine(KillSwitch killSwitch, RiskLimits limits, StringRedisTemplate redis, AccountClient account,
               java.util.function.Supplier<LocalDate> today, RiskStore store) {
        this.killSwitch = killSwitch; this.limits = limits; this.redis = redis; this.account = account; this.today = today; this.store = store;
    }

    /**
     * 流程（review #4 #5 #8 後）：
     *  1. 數量合法、限價偏離（任何單都檢查）
     *  2. 判斷「減倉」：合約 reduceOnly、現貨 SELL。減倉不受次數 / 名目上限、日虧損、集中度限制（只計數）
     *  3. 熔斷開著：只放行「確認是減倉」的單 —— 合約 reduceOnly；現貨 SELL 要帳上真的有那麼多幣
     *  4. 開倉：統一帳戶保護 → 權益（無效 / 示意餘額在正式網一律拒）→ 日虧損 → 次數 / 名目 / 集中度 / 合約曝險（含在途）原子檢查並預留
     */
    public RiskDecision evaluate(Map<String, Object> intent) {
        double qty = num(intent.get("quantity"));
        if (qty <= 0) return reject("數量不合法");
        double maxQty = limits.maxOrderQty();
        if (maxQty > 0 && qty > maxQty) return new RiskDecision(false, "超過單筆數量上限 " + maxQty, maxQty);

        String symbol = String.valueOf(intent.getOrDefault("symbol", ""));
        String side = String.valueOf(intent.getOrDefault("side", "BUY")).toUpperCase();
        String acct = acct(intent.get("account"));
        String cid = String.valueOf(intent.getOrDefault("clientOrderId", ""));
        boolean buy = "BUY".equals(side);
        boolean futures = "USDT_M".equalsIgnoreCase(String.valueOf(intent.getOrDefault("market", "SPOT")));
        boolean reduce = Boolean.TRUE.equals(intent.get("reduceOnly")) || "true".equalsIgnoreCase(String.valueOf(intent.get("reduceOnly")));
        boolean reducing = futures ? reduce : !buy;
        double limitPx = num(intent.get("price"));
        double ref = lastPrice(symbol);
        double px = limitPx > 0 ? limitPx : ref;
        double notional = px > 0 ? qty * px : 0;

        // 1. 限價偏離（胖手指）—— 減倉也檢查：限價打錯一樣會虧
        double dev = limits.maxPriceDeviationPct();
        if (limitPx > 0 && ref > 0 && dev > 0) {
            double d = Math.abs(limitPx - ref) / ref;
            if (d > dev) return reject(String.format("限價 %.8g 偏離現價 %.8g 達 %.1f%%（上限 %.0f%%），疑似胖手指",
                    limitPx, ref, d * 100, dev * 100));
        }

        String day = today.get().toString();
        String ordersKey = "risk:orders:" + acct + ":" + day, notionalKey = "risk:notional:" + acct + ":" + day;

        // 2/3. 減倉
        if (reducing) {
            if (killSwitch.isOn() && !futures) {
                // 熔斷中只放行「確認是平倉」的現貨 SELL：帳上（現貨 + 統一帳戶）這個幣的可用量要夠
                Equity eq = equity();
                String base = baseAsset(symbol);
                double held = eq == null ? -1 : eq.free.getOrDefault(base, 0.0);
                if (held < 0) return reject("熔斷中：查不到帳戶持倉，無法確認這張 SELL 是平倉，先擋下");
                if (qty > held * (1 + 1e-9)) return reject(String.format("熔斷中只允許平倉：SELL %.8g %s 超過持有 %.8g", qty, base, held));
            }
            store.count(ordersKey, notionalKey, notional);
            return new RiskDecision(true, killSwitch.isOn() ? "熔斷中：放行減倉 / 平倉" : "ok（減倉）", null);
        }

        // 以下是開倉
        if (killSwitch.isOn()) return reject("熔斷已啟動: " + killSwitch.reason() + "（只允許減倉 / 平倉）");
        if (futures) {   // 統一帳戶風險保護（account-service 依 uniMMR / 帳戶狀態寫入）
            String pm = null;
            try { pm = redis.opsForValue().get("risk:pm-guard"); } catch (Exception ignore) {}
            if (pm != null) return reject("統一帳戶風險保護：" + pm + "，停止合約開倉；平倉 / 減倉照常");
        }

        double maxLoss = limits.maxDailyLossPct(), maxPos = futures ? 0 : limits.maxPositionPct();
        double maxLev = futures ? limits.maxFuturesLeverage() : 0;
        double headroom = -1;
        String kind = "";
        if (maxLoss > 0 || maxPos > 0 || maxLev > 0) {
            Equity eq = equity();
            if (eq == null) {
                if (placeholderOk()) {
                    log.debug("未設定金鑰（示意餘額、非正式網）：略過權益類檢查 {}", symbol);
                } else if (limits.failClosed()) {
                    return reject("帳戶權益無效（查詢失敗 / 資料不完整 / 示意餘額），依 fail-closed 拒絕開倉；減倉 / 平倉不受限");
                } else log.warn("取不到帳戶權益，fail-closed=false 放行 {}", symbol);
            } else {
                // 日虧損鎖
                if (maxLoss > 0 && eq.total > 0) {
                    double dayStart = dayStartEquity(acct, eq.total);
                    if (dayStart > 0) {
                        double dd = 1 - eq.total / dayStart;
                        if (dd >= maxLoss)
                            return reject(String.format("今日虧損 %.1f%% 已達上限 %.1f%%（日初權益 %.0f → %.0f），停止開倉；減倉 / 平倉不受限",
                                    dd * 100, maxLoss * 100, dayStart, eq.total));
                    }
                }
                // 集中度 / 合約曝險的「剩餘額度」：在途（已核可、未反映到持倉）的單在 store 裡一起算
                if (eq.total > 0 && notional > 0) {
                    if (futures && maxLev > 0) { kind = "fut"; headroom = Math.max(0, maxLev * eq.total - eq.futuresGross); }
                    else if (!futures && maxPos > 0) { kind = "spot"; headroom = Math.max(0, maxPos * eq.total - eq.nonStable); }
                }
            }
        }

        // 次數 / 名目 / 曝險：原子檢查並預留（並發下單不會一起穿過上限）
        RiskStore.Result r;
        try {
            r = store.reserve(ordersKey, notionalKey, "risk:pending:" + acct, limits.maxDailyOrders(), limits.maxDailyNotional(),
                    notional, cid, System.currentTimeMillis(), PENDING_WINDOW_MS, kind, headroom);
        } catch (Exception e) {
            log.error("風控計數 / 預留失敗（Redis）：{}", e.getMessage());
            return reject("風控儲存無法使用，停止開倉（減倉 / 平倉不受影響）");
        }
        return switch (r.code()) {
            case 0 -> new RiskDecision(true, "ok", null);
            case 1 -> reject("今日下單次數已達上限 " + limits.maxDailyOrders() + "（" + acct + "）");
            case 2 -> reject(String.format("今日名目總額 %.0f + 本單 %.0f 超過上限 %.0f USDT", r.value(), notional, limits.maxDailyNotional()));
            default -> futures
                    ? reject(String.format("合約總曝險超過上限 %.2f 倍（含 %.0f USDT 在途未成交），本單 %.0f", maxLev, r.value(), notional))
                    : reject(String.format("買入後持倉佔比超過上限 %.0f%%（含 %.0f USDT 在途未成交），本單 %.0f", maxPos * 100, r.value(), notional));
        };
    }

    /** 沒設金鑰的示意餘額：只有非正式網（測試網 / 模擬 / 開發）才允許略過權益檢查。 */
    private boolean placeholderOk() { return lastPlaceholder && !limits.mainnet(); }

    static String baseAsset(String symbol) {
        String s = symbol.toUpperCase();
        for (String q : List.of("USDT", "USDC", "FDUSD", "BUSD", "BTC", "ETH", "BNB")) if (s.endsWith(q) && s.length() > q.length()) return s.substring(0, s.length() - q.length());
        return s;
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
        m.put("futuresGross", eq == null ? null : round2(eq.futuresGross));
        m.put("futuresLeverage", eq == null || eq.total <= 0 ? null : round4(eq.futuresGross / eq.total));
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
    /** total = 現貨 + 合約保證金餘額；nonStable = 非穩定幣市值；futuresGross = 合約持倉名目絕對值；free = 各幣可用量。 */
    record Equity(double total, double nonStable, double futuresGross, Map<String, Double> free) {
        Equity(double total, double nonStable) { this(total, nonStable, 0, Map.of()); }
        Equity(double total, double nonStable, double futuresGross) { this(total, nonStable, futuresGross, Map.of()); }
    }
    private volatile boolean lastPlaceholder = false;

    /** 權益與非穩定幣市值；拿不到回 null。account-service 的 /balance 已含各資產 valueUsdt。 */
    Equity equity() {
        if (account == null) return null;
        try {
            Map<String, Object> bal = account.balance().data();
            lastPlaceholder = false;
            if (bal == null) return null;
            // review #4：示意 / 查詢失敗的假餘額（real=false 或 valid=false）不可拿來算風控
            if (Boolean.FALSE.equals(bal.get("valid")) || Boolean.FALSE.equals(bal.get("real"))) {
                lastPlaceholder = Boolean.TRUE.equals(bal.get("placeholder"));
                return null;
            }
            if (!(bal.get("totalUsdt") instanceof Number t)) return null;
            // 合約帳戶查不到時總額少一截：拿來算日虧損會誤鎖，當作「取不到權益」交給 fail-closed 處理
            if (Boolean.TRUE.equals(bal.get("partial"))) { log.warn("帳戶權益不完整（合約查詢失敗）"); return null; }
            double futGross = bal.get("futures") instanceof Map<?, ?> fm && fm.get("grossNotional") instanceof Number g ? g.doubleValue() : 0;
            double nonStable = 0;
            Map<String, Double> free = new java.util.HashMap<>();
            Object list = bal.get("balances");
            if (list instanceof List<?> rows) {
                for (Object o : rows) {
                    if (!(o instanceof Map<?, ?> r)) continue;
                    String asset = String.valueOf(r.get("asset")).toUpperCase();
                    if (r.get("free") instanceof Number f) free.merge(asset, f.doubleValue(), Double::sum);
                    if (STABLES.contains(asset)) continue;
                    if (r.get("valueUsdt") instanceof Number v) nonStable += v.doubleValue();
                }
            }
            return new Equity(t.doubleValue(), nonStable, futGross, free);
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

    private long counter(String name, String acct) { return (long) store.get("risk:" + name + ":" + acct + ":" + today.get()); }
    private double counterD(String name, String acct) { return store.get("risk:" + name + ":" + acct + ":" + today.get()); }
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
