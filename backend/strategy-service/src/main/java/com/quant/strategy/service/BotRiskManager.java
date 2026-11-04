package com.quant.strategy.service;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.strategy.core.StrategyContextImpl;
import com.quant.strategy.position.StrategyPositionBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * 機器人層級風控（獨立於策略訊號）：
 * <ol>
 *   <li><b>出場</b>：ATR 停損、ATR 移動停利、固定 % 停利。每個 tick 檢查，觸發就市價全賣。策略只管「何時進」，出場一定有底線。</li>
 *   <li><b>同時持倉上限 + 訊號排名</b>：同一根 K 收盤時多個標的同時出 BUY，先收集 {@value #RANK_WINDOW_MS}ms，
 *       依「ATR 標準化動能」排序，只做前 K 名（K = 上限 − 已佔用）。小資金不會被 20 個幣切碎。</li>
 *   <li><b>機器人熔斷</b>：連虧 N 筆，或本次上線損益（已實現 + 未實現）≤ −X USDT → 暫停進場並通知；
 *       暫停期間既有部位的停損/停利照樣執行，不會放著不管。</li>
 *   <li><b>冷卻</b>：出場後同一標的 N 根收盤 K 內不再進場，避免停損後馬上又被同一訊號拉進去。</li>
 * </ol>
 *   <li><b>盤勢過濾</b>：每個標的用 ADX(14) 判斷趨勢盤 / 盤整盤，用「近 14 根 ATR% ÷ 60 根中位數」判斷是否異常高波動。
 *       趨勢類策略（均線、突破、動量…）只在趨勢盤或中性盤進場；均值回歸類（RSI、布林、Z 分數、網格…）只在盤整或中性盤進場；
 *       異常高波動時全部暫停進場。很多策略不是爛，是用錯盤勢。</li>
 * 參數放在各機器人的 params（可在策略頁「參數」調整，免重啟、不重新暖機）。
 */
@Service
public class BotRiskManager implements StrategyContextImpl.BuyRouter {

    private static final Logger log = LoggerFactory.getLogger(BotRiskManager.class);
    static final long RANK_WINDOW_MS = 2000;
    static final int ATR_N = 14, MOM_N = 10, HIST = 60;
    static final double FALLBACK_STOP_PCT = 2.0;   // ATR 還沒算出來（暖機）時的停損 %

    /** 風控參數鍵與預設值（0 = 關閉該項） */
    public static final Map<String, Double> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("stopAtr", 2.0);          // 停損 = 進場價 − k × ATR
        DEFAULTS.put("trailAtr", 3.0);         // 移動停利 = 進場後最高價 − k × ATR（獲利後才啟動）
        DEFAULTS.put("takeProfitPct", 0.0);    // 固定停利 %
        DEFAULTS.put("maxPositions", 3.0);     // 同時持倉上限（ALL 標的機器人才有意義）
        DEFAULTS.put("maxConsecLosses", 3.0);  // 連虧幾筆暫停
        DEFAULTS.put("maxLossUsdt", 3.0);      // 本次上線虧損上限（USDT）
        DEFAULTS.put("cooldownBars", 5.0);     // 出場後冷卻幾根收盤 K
        DEFAULTS.put("regimeFilter", 1.0);     // 1 = 依盤勢過濾進場
        DEFAULTS.put("blockHighVol", 1.0);     // 1 = 異常高波動時不進場
    }

    // ── 盤勢 ──
    public enum Regime { TREND, RANGE, MIXED, UNKNOWN }
    static final double ADX_TREND = 25, ADX_RANGE = 20, HIGH_VOL_RATIO = 2.5;
    static final Set<String> TREND_TYPES = Set.of("ma", "ema", "wma", "tema", "macd", "trix", "adx", "supertrend", "sar",
            "ichimoku", "donchian", "bollbreak", "keltner", "atr", "volbreak", "dualthrust", "momentum", "roc", "aroon", "heikin");
    static final Set<String> MEANREV_TYPES = Set.of("rsi", "grid", "cci", "williams", "stochrsi", "boll", "kdj", "zscore", "vwap");
    /** 策略家族：trend / meanrev / any */
    public static String family(String type) {
        String t = type == null ? "" : type.toLowerCase();
        return TREND_TYPES.contains(t) ? "trend" : MEANREV_TYPES.contains(t) ? "meanrev" : "any";
    }
    public static boolean isRiskKey(String k) { return DEFAULTS.containsKey(k); }

    private final StrategyPositionBook book;
    private final NotifyClient notify;
    private volatile Function<String, Map<String, Object>> params = id -> Map.of();
    private volatile Function<String, String> names = id -> id;
    private volatile Function<String, String> typeOf = id -> "ma";
    private BanditAllocator bandit;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBandit(BanditAllocator b) { this.bandit = b; }
    private final Map<String, Double> prices;

    // 每個標的的已收盤 K 歷史（ATR / 動能）
    private final Map<String, Deque<double[]>> bars = new ConcurrentHashMap<>();   // [high, low, close]
    private final Map<String, Long> closedCount = new ConcurrentHashMap<>();         // symbol -> 收盤根數（冷卻計時）
    // 每機器人狀態
    private final Map<String, Map<String, Double>> peak = new ConcurrentHashMap<>(); // id -> symbol -> 進場後最高價
    private final Map<String, Map<String, Long>> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecLosses = new ConcurrentHashMap<>();
    private final Map<String, Double> realized = new ConcurrentHashMap<>();
    private final Map<String, String> paused = new ConcurrentHashMap<>();            // id -> 原因
    private final Map<String, StrategyContextImpl> ctxOf = new ConcurrentHashMap<>();
    private final Map<String, List<OrderIntent>> buffer = new ConcurrentHashMap<>();
    private final Set<String> flushScheduled = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bot-risk"); t.setDaemon(true); return t;
    });

    @org.springframework.beans.factory.annotation.Autowired
    public BotRiskManager(StrategyPositionBook book, NotifyClient notify) {
        this(book, notify, new ConcurrentHashMap<>());
    }
    BotRiskManager(StrategyPositionBook book, NotifyClient notify, Map<String, Double> prices) {
        this.book = book; this.notify = notify; this.prices = prices;
        book.setCloseListener(this::onClose);
    }

    void bind(Function<String, Map<String, Object>> params, Function<String, String> names, Map<String, Double> livePrices) {
        bind(params, names, id -> "ma", livePrices);
    }
    void bind(Function<String, Map<String, Object>> params, Function<String, String> names,
              Function<String, String> typeOf, Map<String, Double> livePrices) {
        this.params = params; this.names = names; this.typeOf = typeOf;
        if (livePrices != null && livePrices != this.prices) this.prices.putAll(livePrices);
    }

    public double p(String id, String key) {
        Map<String, Object> m = params.apply(id);
        Object v = m == null ? null : m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) try { return Double.parseDouble(s); } catch (NumberFormatException ignore) {}
        return DEFAULTS.getOrDefault(key, 0.0);
    }

    // ── 行情 ──
    /** 每根 K（含未收盤 tick）呼叫一次；收盤的才進歷史。 */
    public void onKline(Kline k) {
        prices.put(k.symbol(), k.close());
        if (!k.closed()) return;
        Deque<double[]> d = bars.computeIfAbsent(k.symbol(), s -> new ConcurrentLinkedDeque<>());
        d.addLast(new double[]{k.high(), k.low(), k.close()});
        while (d.size() > HIST) d.removeFirst();
        closedCount.merge(k.symbol(), 1L, Long::sum);
    }

    public double atr(String symbol) {
        Deque<double[]> d = bars.get(symbol);
        if (d == null || d.size() < ATR_N + 1) return 0;
        List<double[]> l = new ArrayList<>(d);
        double sum = 0;
        for (int i = l.size() - ATR_N; i < l.size(); i++) {
            double[] c = l.get(i), prev = l.get(i - 1);
            sum += Math.max(c[0] - c[1], Math.max(Math.abs(c[0] - prev[2]), Math.abs(c[1] - prev[2])));
        }
        return sum / ATR_N;
    }

    /** 排名分數：近 MOM_N 根的漲幅 ÷ ATR（跨幣可比）。資料不足 = 0。 */
    double score(String symbol) {
        Deque<double[]> d = bars.get(symbol);
        double a = atr(symbol);
        if (d == null || d.size() <= MOM_N || a <= 0) return 0;
        List<double[]> l = new ArrayList<>(d);
        return (l.get(l.size() - 1)[2] - l.get(l.size() - 1 - MOM_N)[2]) / a;
    }

    /** Wilder ADX(14)。資料不足回 -1。 */
    public double adx(String symbol) {
        Deque<double[]> d = bars.get(symbol);
        int n = ATR_N;
        if (d == null || d.size() < 2 * n + 1) return -1;
        List<double[]> l = new ArrayList<>(d);
        double tr = 0, pdm = 0, mdm = 0, adx = 0; int dxCount = 0;
        for (int i = 1; i < l.size(); i++) {
            double[] c = l.get(i), p = l.get(i - 1);
            double t = Math.max(c[0] - c[1], Math.max(Math.abs(c[0] - p[2]), Math.abs(c[1] - p[2])));
            double up = c[0] - p[0], dn = p[1] - c[1];
            double pd = up > dn && up > 0 ? up : 0, md = dn > up && dn > 0 ? dn : 0;
            if (i <= n) { tr += t; pdm += pd; mdm += md; if (i < n) continue; }
            else { tr = tr - tr / n + t; pdm = pdm - pdm / n + pd; mdm = mdm - mdm / n + md; }
            double pdi = tr > 0 ? 100 * pdm / tr : 0, mdi = tr > 0 ? 100 * mdm / tr : 0;
            double dx = pdi + mdi > 0 ? 100 * Math.abs(pdi - mdi) / (pdi + mdi) : 0;
            if (dxCount < n) { adx += dx; dxCount++; if (dxCount == n) adx /= n; }
            else adx = (adx * (n - 1) + dx) / n;
        }
        return dxCount < n ? -1 : adx;
    }

    /** 近 14 根平均 TR% ÷ 整段 TR% 中位數。>2.5 視為異常高波動。資料不足回 0。 */
    public double volRatio(String symbol) {
        Deque<double[]> d = bars.get(symbol);
        if (d == null || d.size() < 30) return 0;
        List<double[]> l = new ArrayList<>(d);
        List<Double> trp = new ArrayList<>();
        for (int i = 1; i < l.size(); i++) {
            double[] c = l.get(i), p = l.get(i - 1);
            double t = Math.max(c[0] - c[1], Math.max(Math.abs(c[0] - p[2]), Math.abs(c[1] - p[2])));
            trp.add(p[2] > 0 ? t / p[2] : 0);
        }
        List<Double> sorted = new ArrayList<>(trp); Collections.sort(sorted);
        double med = sorted.get(sorted.size() / 2);
        double recent = trp.subList(trp.size() - ATR_N, trp.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return med > 0 ? recent / med : 0;
    }

    public Regime regime(String symbol) {
        double a = adx(symbol);
        if (a < 0) return Regime.UNKNOWN;
        return a >= ADX_TREND ? Regime.TREND : a <= ADX_RANGE ? Regime.RANGE : Regime.MIXED;
    }

    /** 給 UI：每個標的的盤勢。 */
    public Map<String, Map<String, Object>> regimes() {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        for (String s : bars.keySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            double a = adx(s), v = volRatio(s);
            m.put("regime", regime(s).name());
            m.put("adx", a < 0 ? null : Math.round(a * 10) / 10.0);
            m.put("volRatio", Math.round(v * 100) / 100.0);
            m.put("highVol", v >= HIGH_VOL_RATIO);
            out.put(s, m);
        }
        return out;
    }

    /** 盤勢不適合這個機器人進場時回原因，否則 null。 */
    String regimeBlock(String id, String sym) {
        if (p(id, "blockHighVol") > 0 && volRatio(sym) >= HIGH_VOL_RATIO)
            return String.format("異常高波動（%.1f×），暫不進場", volRatio(sym));
        if (p(id, "regimeFilter") <= 0) return null;
        String fam = family(typeOf.apply(id));
        Regime r = regime(sym);
        if ("trend".equals(fam) && r == Regime.RANGE) return "盤整盤（ADX " + Math.round(adx(sym)) + "），趨勢策略不進場";
        if ("meanrev".equals(fam) && r == Regime.TREND) return "趨勢盤（ADX " + Math.round(adx(sym)) + "），均值回歸策略不進場";
        return null;
    }

    // ── 1. 出場 ──
    /** 對運行中的機器人逐 tick 檢查停損 / 移動停利 / 停利。暫停中的機器人也照樣檢查。 */
    public void checkExits(String id, Kline k, StrategyContextImpl ctx) {
        ctxOf.put(id, ctx);
        if (!book.isReconciled()) return;
        String sym = k.symbol();
        double q = book.position(id, sym);
        if (q <= 1e-12) { Map<String, Double> pk = peak.get(id); if (pk != null) pk.remove(sym); return; }
        if (book.isPending(id, sym)) return;
        double px = k.close();
        double entry = book.avgCost(id, sym);
        if (entry <= 0) { book.seedCost(id, sym, px); entry = px; }
        double hi = peak.computeIfAbsent(id, x -> new ConcurrentHashMap<>()).merge(sym, Math.max(k.high(), px), Math::max);
        String why = exitReason(id, sym, px, entry, hi);
        if (why == null) return;
        ctx.exit(sym, why);
        ctx.markOutcome(why + "出場 @" + fmt(px));
        peak.get(id).remove(sym);
        cooldownUntil.computeIfAbsent(id, x -> new ConcurrentHashMap<>())
                .put(sym, closedCount.getOrDefault(sym, 0L) + (long) p(id, "cooldownBars"));
        send(String.format(":octagonal_sign: [%s] %s %s出場 @ %s（進場 %s，%+.2f%%）", names.apply(id), sym, why,
                fmt(px), fmt(entry), (px / entry - 1) * 100));
    }

    /** 回傳觸發的出場原因，未觸發回 null。 */
    String exitReason(String id, String sym, double px, double entry, double hi) {
        double a = atr(sym);
        double stopK = p(id, "stopAtr"), trailK = p(id, "trailAtr"), tp = p(id, "takeProfitPct");
        if (stopK > 0) {
            double stop = a > 0 ? entry - stopK * a : entry * (1 - FALLBACK_STOP_PCT / 100);
            if (px <= stop) return "停損";
        }
        if (trailK > 0 && a > 0 && hi > entry) {
            double trail = hi - trailK * a;
            if (trail > entry && px <= trail) return "移動停利";   // 只在已經有獲利時鎖利
        }
        if (tp > 0 && px >= entry * (1 + tp / 100)) return "停利";
        return null;
    }

    /** 給 UI：各持倉的進場價、停損、移動停利、停利價。 */
    public List<Map<String, Object>> exitLevels(String id) {
        List<Map<String, Object>> out = new ArrayList<>();
        book.snapshot(id).forEach((sym, q) -> {
            if (q <= 1e-12) return;
            double entry = book.avgCost(id, sym), a = atr(sym), px = prices.getOrDefault(sym, 0.0);
            double hi = peak.getOrDefault(id, Map.of()).getOrDefault(sym, Math.max(entry, px));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", sym); m.put("qty", q); m.put("entry", entry); m.put("last", px);
            double stopK = p(id, "stopAtr"), trailK = p(id, "trailAtr"), tp = p(id, "takeProfitPct");
            m.put("stop", stopK <= 0 || entry <= 0 ? 0 : a > 0 ? entry - stopK * a : entry * (1 - FALLBACK_STOP_PCT / 100));
            double trail = trailK > 0 && a > 0 ? hi - trailK * a : 0;
            m.put("trail", trail > entry ? trail : 0);
            m.put("takeProfit", tp > 0 && entry > 0 ? entry * (1 + tp / 100) : 0);
            out.add(m);
        });
        return out;
    }

    // ── 2. 同時持倉上限 + 排名 ──
    @Override
    public boolean route(StrategyContextImpl ctx, OrderIntent intent) {
        String id = ctx.strategyId(), sym = intent.symbol();
        ctxOf.put(id, ctx);
        String why = paused.get(id);
        if (why != null) { ctx.markOutcome("已暫停進場：" + why); return true; }
        Long until = cooldownUntil.getOrDefault(id, Map.of()).get(sym);
        if (until != null && closedCount.getOrDefault(sym, 0L) < until) {
            ctx.markOutcome("冷卻中（出場後 " + (long) p(id, "cooldownBars") + " 根內不進場）"); return true;
        }
        String rb = regimeBlock(id, sym);
        if (rb != null) { ctx.markOutcome(sym + " " + rb); return true; }
        buffer.computeIfAbsent(id, x -> Collections.synchronizedList(new ArrayList<>())).add(intent);
        ctx.markOutcome("排名中…");
        if (flushScheduled.add(id)) exec.schedule(() -> flush(id), RANK_WINDOW_MS, TimeUnit.MILLISECONDS);
        return true;
    }

    void flush(String id) {
        flushScheduled.remove(id);
        List<OrderIntent> l = buffer.remove(id);
        StrategyContextImpl ctx = ctxOf.get(id);
        if (l == null || l.isEmpty() || ctx == null) return;
        Map<String, OrderIntent> uniq = new LinkedHashMap<>();
        synchronized (l) { for (OrderIntent i : l) uniq.putIfAbsent(i.symbol(), i); }
        List<OrderIntent> ranked = new ArrayList<>(uniq.values());
        ranked.sort(Comparator.comparingDouble((OrderIntent i) -> score(i.symbol())).reversed());
        int cap = Math.max(1, (int) p(id, "maxPositions"));
        int slots = cap - book.openSlots(id);
        int taken = 0;
        for (OrderIntent i : ranked) {
            if (taken < slots && !book.isPending(id, i.symbol()) && book.position(id, i.symbol()) <= 1e-12) {
                ctx.execute(i); taken++;
            }
        }
        if (ranked.size() > taken) {
            String skipped = ranked.subList(taken, ranked.size()).stream().map(OrderIntent::symbol).limit(5)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            if (taken == 0) ctx.markOutcome("已達同時持倉上限 " + cap + "，略過 " + skipped);
            log.info("[{}] {} 個 BUY 訊號，持倉上限 {}（已佔 {}），送出 {} 個，略過 {}", id, ranked.size(), cap,
                    cap - slots, taken, skipped);
        }
    }

    // ── 3. 機器人熔斷 ──
    void onClose(String id, String sym, double pnl, double entryNotional) {
        realized.merge(id, pnl, Double::sum);
        if (bandit != null && entryNotional > 0) bandit.record(id, pnl / entryNotional);
        if (pnl < 0) consecLosses.merge(id, 1, Integer::sum); else consecLosses.put(id, 0);
        log.info("[{}] {} 平倉損益 {}，連虧 {}，本次累計 {}", id, sym, fmt(pnl), consecLosses.get(id), fmt(realized.get(id)));
        evaluate(id);
    }

    /** 每 15 秒以「已實現 + 未實現」重新檢查一次，部位還沒平也能熔斷。 */
    @Scheduled(initialDelay = 30_000, fixedDelay = 15_000)
    public void sweep() { for (String id : new ArrayList<>(ctxOf.keySet())) evaluate(id); }

    void evaluate(String id) {
        if (paused.containsKey(id) || !ctxOf.containsKey(id)) return;
        int n = consecLosses.getOrDefault(id, 0);
        int maxN = (int) p(id, "maxConsecLosses");
        if (maxN > 0 && n >= maxN) { pause(id, "連虧 " + n + " 筆"); return; }
        double maxLoss = p(id, "maxLossUsdt");
        double total = sessionPnl(id);
        if (maxLoss > 0 && total <= -maxLoss) pause(id, String.format("本次上線虧損 %.2f USDT ≥ 上限 %.2f", -total, maxLoss));
    }

    public double sessionPnl(String id) {
        double u = 0;
        for (Map.Entry<String, Double> e : book.snapshot(id).entrySet()) {
            double q = e.getValue(), entry = book.avgCost(id, e.getKey()), px = prices.getOrDefault(e.getKey(), 0.0);
            if (q > 1e-12 && entry > 0 && px > 0) u += (px - entry) * q;
        }
        return realized.getOrDefault(id, 0.0) + u;
    }

    void pause(String id, String why) {
        if (paused.putIfAbsent(id, why) != null) return;
        log.warn("[{}] 機器人熔斷：{} → 暫停進場（既有部位的停損/停利照常）", id, why);
        StrategyContextImpl c = ctxOf.get(id);
        if (c != null) c.markOutcome("已暫停進場：" + why);
        send(":rotating_light: 機器人熔斷 [" + names.apply(id) + "] " + why + " → 暫停進場；既有部位的停損/停利照常。到策略頁按「恢復」重新開始");
    }

    public String pausedReason(String id) { return paused.get(id); }
    public int consecLosses(String id) { return consecLosses.getOrDefault(id, 0); }
    public double realized(String id) { return realized.getOrDefault(id, 0.0); }

    /** 上線 / 恢復：清空本次統計。 */
    public void reset(String id) {
        paused.remove(id); consecLosses.remove(id); realized.remove(id);
        Map<String, Long> cd = cooldownUntil.get(id); if (cd != null) cd.clear();
    }
    /** 下線：不再追蹤（部位由使用者決定是否平倉）。 */
    public void forget(String id) { reset(id); ctxOf.remove(id); buffer.remove(id); peak.remove(id); }

    private void send(String text) {
        try { notify.send(Map.of("text", text)); } catch (Exception e) { log.debug("通知失敗（忽略）: {}", e.getMessage()); }
    }
    private static String fmt(double v) {
        return java.math.BigDecimal.valueOf(v).round(new java.math.MathContext(6)).stripTrailingZeros().toPlainString();
    }
}
