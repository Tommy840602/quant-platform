package com.quant.strategy.service;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.risk.BarStats;
import com.quant.common.risk.RiskRules;
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
    static final double FALLBACK_STOP_PCT = RiskRules.FALLBACK_STOP_PCT;
    static final double HIGH_VOL_RATIO = RiskRules.HIGH_VOL_RATIO;
    /** 風控參數鍵與預設值（共用 {@link RiskRules#DEFAULTS}） */
    public static final Map<String, Double> DEFAULTS = RiskRules.DEFAULTS;
    public static String family(String type) { return RiskRules.family(type); }
    public static boolean isRiskKey(String k) { return RiskRules.isRiskKey(k); }

    /** 重啟要保住的狀態（移動停利的最高價、熔斷暫停）由 runner 注入的 store 落庫。 */
    public interface StateStore {
        void savePeak(String id, String symbol, double peak);
        void deletePeak(String id, String symbol);
        void savePaused(String id, String reasonOrNull);
    }
    private volatile StateStore store;
    public void setStore(StateStore s) { this.store = s; }
    private final Map<String, Double> savedPeak = new ConcurrentHashMap<>();

    private final StrategyPositionBook book;
    private final NotifyClient notify;
    private volatile Function<String, Map<String, Object>> params = id -> Map.of();
    private volatile Function<String, String> names = id -> id;
    private volatile Function<String, String> typeOf = id -> "ma";
    private volatile Function<String, String> intervalOf = id -> "1m";
    public void setIntervalOf(Function<String, String> f) { this.intervalOf = f; }
    /** 第一次看到某 (標的, 週期) 時抓歷史 K 線補滿 ATR / ADX 的計算窗；null = 不暖機（單元測試）。 */
    private volatile java.util.function.BiFunction<String, String, List<Kline>> history;
    public void setHistory(java.util.function.BiFunction<String, String, List<Kline>> h) { this.history = h; }
    private BanditAllocator bandit;
    /** 實盤平倉回報（給影子引擎 / 晉升管線記錄來回交易）。 */
    public interface TradeHook { void onLiveTrade(StrategyPositionBook.Close c); }
    private volatile TradeHook tradeHook;
    public void setTradeHook(TradeHook h) { this.tradeHook = h; }
    private volatile java.util.function.DoubleSupplier equity = () -> 0;
    public void setEquity(java.util.function.DoubleSupplier e) { this.equity = e; }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBandit(BanditAllocator b) { this.bandit = b; }
    private final Map<String, Double> prices;

    // 每個標的的已收盤 K 歷史（ATR / ADX / 動能），計算與回測共用 common.risk.BarStats
    private final Map<String, BarStats> bars = new ConcurrentHashMap<>();
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
        book.setCloseListener(this::onLiveClose);
    }

    void bind(Function<String, Map<String, Object>> params, Function<String, String> names, Map<String, Double> livePrices) {
        bind(params, names, id -> "ma", livePrices);
    }
    void bind(Function<String, Map<String, Object>> params, Function<String, String> names,
              Function<String, String> typeOf, Map<String, Double> livePrices) {
        this.params = params; this.names = names; this.typeOf = typeOf;
        if (livePrices != null && livePrices != this.prices) this.prices.putAll(livePrices);
    }

    public double p(String id, String key) { return RiskRules.param(params.apply(id), key); }
    private Map<String, Object> pm(String id) { Map<String, Object> m = params.apply(id); return m == null ? Map.of() : m; }
    private static String key(String symbol, String interval) { return symbol + "@" + (interval == null || interval.isBlank() ? "1m" : interval); }
    /** 1 分 K 的統計（UI 盤勢面板、無機器人情境）。 */
    private BarStats st(String symbol) { return bars.getOrDefault(key(symbol, "1m"), EMPTY); }
    /** 該機器人自己週期的統計：停損 ATR、盤勢、排名、冷卻都用它。 */
    public BarStats stats(String id, String symbol) { return bars.getOrDefault(key(symbol, intervalOf.apply(id)), EMPTY); }
    private static final BarStats EMPTY = new BarStats();
    private long closedCount(String id, String symbol) { return stats(id, symbol).closedCount(); }

    // ── 行情 ──
    /** 每根 K（含未收盤 tick）呼叫一次；收盤的才進歷史。 */
    public void onKline(Kline k) {
        String iv = k.interval() == null || k.interval().isBlank() ? "1m" : k.interval();
        if ("1m".equals(iv)) prices.put(k.symbol(), k.close());
        if (!k.closed()) return;
        String key = key(k.symbol(), iv);
        BarStats s = bars.get(key);
        if (s == null) {
            s = new BarStats();
            var h = history;
            if (h != null) try {
                List<Kline> past = h.apply(k.symbol(), iv);
                if (past != null) for (Kline b : past) if (b.closed() && b.openTime() < k.openTime()) s.add(b.high(), b.low(), b.close());
            } catch (Exception e) { log.debug("風控指標暖機失敗 {}: {}", key, e.getMessage()); }
            bars.put(key, s);
        }
        s.add(k.high(), k.low(), k.close());
    }

    public double atr(String symbol) { return st(symbol).atr(); }
    double atr(String id, String symbol) { return stats(id, symbol).atr(); }
    /** 排名分數：近 10 根漲幅 ÷ ATR（跨幣可比）。 */
    public double score(String symbol) { return st(symbol).score(); }
    public double adx(String symbol) { return st(symbol).adx(); }
    public double volRatio(String symbol) { return st(symbol).volRatio(); }
    public RiskRules.Regime regime(String symbol) { return st(symbol).regime(); }

    /**
     * 波動率目標下單量：每筆最多虧「權益 × riskPerTradePct%」，數量 = 風險預算 ÷ 停損距離；上限 = 權益 × sizeCapPct%。
     * 回 0 = 未啟用（沿用每單名目）。最低 5 USDT 的夾值在 context 做。
     */
    public double sizeQty(String id, String sym, double px) {
        return RiskRules.sizeQty(equity.getAsDouble(), px, atr(id, sym), pm(id));
    }

    /** 給 UI：每個標的的盤勢。 */
    public Map<String, Map<String, Object>> regimes() {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        for (String k : bars.keySet()) {
            if (!k.endsWith("@1m")) continue;
            String s = k.substring(0, k.length() - 3);
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
    String regimeBlock(String id, String sym) { return RiskRules.regimeBlock(typeOf.apply(id), stats(id, sym), pm(id)); }

    // ── 1. 出場 ──
    /** 對運行中的機器人逐 tick 檢查停損 / 移動停利 / 停利。暫停中的機器人也照樣檢查。 */
    public void checkExits(String id, Kline k, StrategyContextImpl ctx) {
        ctxOf.put(id, ctx);
        boolean paper = ctx.isPaper();
        if (!paper && !book.isReconciled()) return;
        String sym = k.symbol();
        double q = ctx.position(sym);
        if (Math.abs(q) <= 1e-12) { Map<String, Double> pk = peak.get(id); if (pk != null && pk.remove(sym) != null) dropPeak(id, sym); return; }
        if (ctx.pending(sym)) return;
        double px = k.close();
        double entry = ctx.entry(sym);
        if (entry <= 0) { if (!paper) book.seedCost(id, sym, px); entry = px; }
        boolean shortPos = q < 0;
        // 多單記進場後最高價、空單記最低價（移動停利基準）
        double hi = shortPos
                ? peak.computeIfAbsent(id, x -> new ConcurrentHashMap<>()).merge(sym, Math.min(k.low() > 0 ? k.low() : px, px), Math::min)
                : peak.computeIfAbsent(id, x -> new ConcurrentHashMap<>()).merge(sym, Math.max(k.high(), px), Math::max);
        persistPeak(id, sym, hi);
        String why = shortPos ? RiskRules.exitReasonShort(px, entry, hi, atr(id, sym), pm(id)) : exitReason(id, sym, px, entry, hi);
        if (why == null) return;
        ctx.exit(sym, why);
        peak.get(id).remove(sym); dropPeak(id, sym);
        cooldownUntil.computeIfAbsent(id, x -> new ConcurrentHashMap<>())
                .put(sym, closedCount(id, sym) + (long) p(id, "cooldownBars"));
        send(String.format(":octagonal_sign: [%s]%s %s %s%s出場 @ %s（進場 %s，%+.2f%%）", names.apply(id), paper ? "（模擬）" : "",
                sym, shortPos ? "空單" : "", why, fmt(px), fmt(entry), (px / entry - 1) * 100 * (shortPos ? -1 : 1)));
    }

    /** 回傳觸發的出場原因，未觸發回 null。 */
    String exitReason(String id, String sym, double px, double entry, double hi) {
        return RiskRules.exitReason(px, entry, hi, atr(id, sym), pm(id));
    }

    /** 最高價漲超過 0.05% 才寫一次，避免每個 tick 打 DB。 */
    private void persistPeak(String id, String sym, double hi) {
        StateStore s = store; if (s == null) return;
        String key = id + "|" + sym; Double last = savedPeak.get(key);
        if (last != null && Math.abs(hi / last - 1) < 0.0005) return;   // 多單最高價 / 空單最低價變動 < 0.05% 不寫
        savedPeak.put(key, hi);
        try { s.savePeak(id, sym, hi); } catch (Exception e) { log.debug("peak 寫入失敗: {}", e.getMessage()); }
    }
    private void dropPeak(String id, String sym) {
        savedPeak.remove(id + "|" + sym);
        StateStore s = store; if (s != null) try { s.deletePeak(id, sym); } catch (Exception e) { log.debug("peak 刪除失敗: {}", e.getMessage()); }
    }

    /** 重啟後還原：熔斷狀態、本次已實現損益、連虧筆數、各持倉的最高價。 */
    public void restore(String id, String pausedReason, double realizedSinceStart, int consec, Map<String, Double> peaks) {
        if (pausedReason != null && !pausedReason.isBlank()) paused.put(id, pausedReason);
        if (realizedSinceStart != 0) realized.put(id, realizedSinceStart);
        if (consec > 0) consecLosses.put(id, consec);
        if (peaks != null && !peaks.isEmpty()) {
            peak.computeIfAbsent(id, x -> new ConcurrentHashMap<>()).putAll(peaks);
            peaks.forEach((sym, v) -> savedPeak.put(id + "|" + sym, v));
        }
    }

    /** 給 UI：各持倉的進場價、停損、移動停利、停利價。 */
    public List<Map<String, Object>> exitLevels(String id) {
        List<Map<String, Object>> out = new ArrayList<>();
        StrategyContextImpl c = ctxOf.get(id);
        Map<String, Double> snap = c != null ? c.snapshot() : book.snapshot(id);
        snap.forEach((sym, q) -> {
            if (Math.abs(q) <= 1e-12) return;
            double entry = c != null ? c.entry(sym) : book.avgCost(id, sym), a = atr(id, sym), px = prices.getOrDefault(sym, 0.0);
            double hi = peak.getOrDefault(id, Map.of()).getOrDefault(sym, Math.max(entry, px));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", sym); m.put("qty", q); m.put("entry", entry); m.put("last", px);
            if (q < 0) {
                double lo = peak.getOrDefault(id, Map.of()).getOrDefault(sym, Math.min(entry, px > 0 ? px : entry));
                m.put("side", "SHORT");
                m.put("stop", RiskRules.shortStopPrice(entry, a, pm(id)));
                m.put("trail", RiskRules.shortTrailPrice(entry, lo, a, pm(id)));
                m.put("takeProfit", RiskRules.shortTakeProfitPrice(entry, pm(id)));
            } else {
                m.put("side", "LONG");
                m.put("stop", RiskRules.stopPrice(entry, a, pm(id)));
                m.put("trail", RiskRules.trailPrice(entry, hi, a, pm(id)));
                m.put("takeProfit", RiskRules.takeProfitPrice(entry, pm(id)));
            }
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
        if (why != null) { ctx.decide(sym, intent.side(), "已暫停進場：" + why); return true; }
        Long until = cooldownUntil.getOrDefault(id, Map.of()).get(sym);
        if (until != null && closedCount(id, sym) < until) {
            ctx.decide(sym, intent.side(), "冷卻中（出場後 " + (long) p(id, "cooldownBars") + " 根內不進場）"); return true;
        }
        String rb = regimeBlock(id, sym);
        if (rb != null) { ctx.decide(sym, intent.side(), rb); return true; }
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
        // 做多挑漲最強的、做空挑跌最強的
        ranked.sort(Comparator.comparingDouble((OrderIntent i) -> (i.side() == com.quant.common.enums.OrderSide.SELL ? -1 : 1) * stats(id, i.symbol()).score()).reversed());
        int cap = Math.max(1, (int) p(id, "maxPositions"));
        int slots = cap - ctx.openSlots();
        int taken = 0, rank = 0;
        for (OrderIntent i : ranked) {
            rank++;
            if (taken < slots && !ctx.pending(i.symbol()) && Math.abs(ctx.position(i.symbol())) <= 1e-12) {
                ctx.execute(i); taken++;
            } else {
                ctx.decide(i.symbol(), i.side(), String.format("排名第 %d（分數 %.2f），同時持倉上限 %d 已滿", rank, stats(id, i.symbol()).score(), cap));
            }
        }
        if (ranked.size() > taken) {
            String skipped = ranked.subList(taken, ranked.size()).stream().map(OrderIntent::symbol).limit(5)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            log.info("[{}] {} 個 BUY 訊號，持倉上限 {}（已佔 {}），送出 {} 個，略過 {}", id, ranked.size(), cap,
                    cap - slots, taken, skipped);
        }
    }

    // ── 3. 機器人熔斷 ──
    void onLiveClose(StrategyPositionBook.Close c) {
        onClose(c);
        TradeHook h = tradeHook;
        if (h != null) try { h.onLiveTrade(c); } catch (Exception e) { log.warn("trade hook: {}", e.getMessage()); }
    }

    /** 來回交易平倉（實盤 / 模擬盤都走這裡）：連虧、本次損益、Bandit 學習。 */
    public void onClose(StrategyPositionBook.Close c) {
        String id = c.strategyId(), sym = c.symbol(); double pnl = c.pnl();
        realized.merge(id, pnl, Double::sum);
        if (bandit != null && c.entryNotional() > 0) bandit.record(id, c.ret());
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
        StrategyContextImpl c = ctxOf.get(id);
        Map<String, Double> snap = c != null ? c.snapshot() : book.snapshot(id);
        for (Map.Entry<String, Double> e : snap.entrySet()) {
            double q = e.getValue(), px = prices.getOrDefault(e.getKey(), 0.0);
            double entry = c != null ? c.entry(e.getKey()) : book.avgCost(id, e.getKey());
            if (Math.abs(q) > 1e-12 && entry > 0 && px > 0) u += (px - entry) * q;   // 空單 q < 0：價格跌就是賺
        }
        return realized.getOrDefault(id, 0.0) + u;
    }

    void pause(String id, String why) {
        if (paused.putIfAbsent(id, why) != null) return;
        StateStore st = store; if (st != null) try { st.savePaused(id, why); } catch (Exception e) { log.warn("熔斷狀態寫入失敗: {}", e.getMessage()); }
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
        if (paused.remove(id) != null) { StateStore st = store; if (st != null) try { st.savePaused(id, null); } catch (Exception e) { log.warn("熔斷狀態寫入失敗: {}", e.getMessage()); } }
        consecLosses.remove(id); realized.remove(id);
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
