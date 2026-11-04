package com.quant.strategy.service;

import com.quant.common.event.MarketDataEvent;
import com.quant.common.dto.Kline;
import com.quant.common.strategy.StrategyContext;
import com.quant.common.event.Topics;
import com.quant.common.enums.OrderType;
import com.quant.common.enums.OrderSide;
import com.quant.common.dto.OrderIntent;
import com.quant.common.strategy.*;
import com.quant.strategy.core.StrategyContextImpl;
import com.quant.strategy.position.StrategyPositionBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 交易機器人運行管理（見架構文件 §3.2.2）：內建多策略 + 執行期動態建立機器人(ma/rsi/grid)，
 * 各自 context/部位、可分別上下線並行，支援參數熱更新與 symbol 篩選。
 *
 * 策略實例「每個 symbol 一份」：所有策略都把 closes 等狀態放在實例欄位，若一份實例同時吃 20 個幣的 K 線，
 * BTC 的 6 萬會和 DOGE 的 0.1 混進同一條均線。這裡以 factory 依 symbol 懶建實例，狀態天然隔離。
 */
@Service
public class StrategyRunner {

    private static final Logger log = LoggerFactory.getLogger(StrategyRunner.class);

    private final OrderClient orderClient;
    private final NotifyClient notifyClient;
    private final OmsClient omsClient;
    private final StrategyPositionBook book;
    private final Map<String, Double> prices = new ConcurrentHashMap<>();
    /** botId -> 建立新實例的工廠（參數熱更新 = 換工廠並清掉既有實例） */
    private final Map<String, Supplier<Strategy>> factories = new ConcurrentHashMap<>();
    /** botId -> symbol -> 實例 */
    private final Map<String, Map<String, Strategy>> instances = new ConcurrentHashMap<>();
    private final Map<String, StrategyContextImpl> contexts = new ConcurrentHashMap<>();
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private final Map<String, String> symbolFilter = new ConcurrentHashMap<>();  // id -> symbol|null
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    @Value("${order.notional-usdt:0}")   // 每單目標名目(USDT)；0=用策略原始固定數量
    private double orderNotional;

    // ── 資金配置守門（後續 8）──
    @Value("${strategy.max-exposure-pct:1.0}") private double maxExposurePct;          // 運行中機器人最大合計曝險 / 權益
    @Value("${strategy.max-total-allocation:5.0}") private double maxTotalAllocation;  // 每單名目=0（用固定數量）時退而求其次：Σ allocation 上限
    private BotRiskManager risk;            // 機器人層級出場 / 持倉上限 / 熔斷；可為 null（單元測試）
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRiskManager(BotRiskManager r) {
        this.risk = r;
        r.bind(id -> paramsOf.getOrDefault(id, Map.of()), id -> names.getOrDefault(id, id), id -> types.getOrDefault(id, "ma"), null);
        contexts.values().forEach(c -> c.setBuyRouter(r));
        r.setEquity(() -> equityCache);
        r.setIntervalOf(this::intervalOf);
        if (market != null) r.setHistory(this::history);
        wireStore();
        if (shadow != null) { shadow.bind(r, this::stageOf, id -> names.getOrDefault(id, id), notifyClient);
            r.setTradeHook(c -> { if (trips != null) try { trips.insert(c, "LIVE"); } catch (Exception ex) { log.warn("來回紀錄寫入失敗: {}", ex.getMessage()); } }); }
    }
    // ── 晉升管線：PAPER（模擬盤）→ PILOT（真錢最小單）→ LIVE ──
    public static final List<String> STAGES = List.of("PAPER", "PILOT", "LIVE");
    private final Map<String, String> stage = new ConcurrentHashMap<>();
    private final Map<String, Long> stageSince = new ConcurrentHashMap<>();
    private volatile boolean pipelineAuto = true;
    /** 關卡門檻（可由 strategy_settings 的 gate.<key> 覆寫） */
    static final Map<String, Double> GATES = new LinkedHashMap<>();
    static {
        GATES.put("paperMinTrades", 10.0);   // PAPER → PILOT：模擬盤至少 N 筆來回
        GATES.put("paperMinHours", 24.0);    //                 且在模擬盤至少 N 小時
        GATES.put("paperMinPF", 1.2);        //                 獲利因子 ≥
        GATES.put("pilotMinTrades", 10.0);   // PILOT → LIVE：真錢最小單至少 N 筆
        GATES.put("pilotMinPF", 1.1);        //                 獲利因子 ≥
        GATES.put("pilotMaxSlipBps", 20.0);  //                 平均滑價 ≤ N bps
        GATES.put("demotePF", 0.9);          // 降級：最近 10 筆獲利因子 <
    }
    private final Map<String, Double> gateOverride = new ConcurrentHashMap<>();
    double gate(String k) { return gateOverride.getOrDefault(k, GATES.get(k)); }
    public String stageOf(String id) { return stage.getOrDefault(id, "LIVE"); }

    private ShadowEngine shadow;
    private RoundTripRepository trips;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setShadow(ShadowEngine e) {
        this.shadow = e;
        e.bind(risk, this::stageOf, id -> names.getOrDefault(id, id), notifyClient);
        e.attach(book);
        if (decisionLog != null) e.setDecisionSink(decisionLog);
        contexts.values().forEach(c -> c.setExecution(e));
        if (risk != null) risk.setTradeHook(c -> { if (trips != null) try { trips.insert(c, "LIVE"); } catch (Exception ex) { log.warn("來回紀錄寫入失敗: {}", ex.getMessage()); } });
    }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTrips(RoundTripRepository r) { this.trips = r; }
    private PositionStateRepository posState;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPosState(PositionStateRepository p) { this.posState = p; wireStore(); }
    /** 讓風控把「移動停利最高價」「熔斷暫停」寫進 DB。 */
    private void wireStore() {
        if (risk == null) return;
        risk.setStore(new BotRiskManager.StateStore() {
            @Override public void savePeak(String id, String sym, double peak) { if (posState != null) posState.upsert(id, sym, "PEAK", 0, 0, peak); }
            @Override public void deletePeak(String id, String sym) { if (posState != null) posState.delete(id, sym, "PEAK"); }
            @Override public void savePaused(String id, String reason) { if (repo != null) repo.setPaused(id, reason); }
        });
    }
    private DecisionLog decisionLog;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDecisionLog(DecisionLog d) { this.decisionLog = d; if (shadow != null) shadow.setDecisionSink(d); }
    public List<Map<String, Object>> decisions(String bot, String symbol, Long from, Long to, Boolean acted, int limit) {
        return decisionLog == null ? List.of() : decisionLog.query(bot, symbol, from, to, acted, limit);
    }

    // ── 權益快取（波動率目標下單量用） ──
    private volatile double equityCache = 0;
    @Scheduled(initialDelay = 15_000, fixedDelay = 60_000)
    public void refreshEquity() {
        if (accountClient == null) return;
        try { Object t = accountClient.balance().data().get("totalUsdt"); if (t instanceof Number n) equityCache = n.doubleValue(); }
        catch (Exception e) { log.debug("取權益失敗: {}", e.getMessage()); }
    }

    /**
     * 重啟後把「只在記憶體裡」的狀態接回來（後續 A1）：
     * 模擬盤 / 影子部位、移動停利最高價、熔斷暫停、本次上線的已實現損益與連虧筆數、影子已實現損益、滑價樣本。
     */
    void restoreRuntimeState() {
        int pos = shadow == null ? 0 : shadow.restore(factories.keySet());
        Map<String, String> pausedMap = Map.of();
        try { if (repo != null) pausedMap = repo.pausedReasons(); } catch (Exception e) { log.warn("讀取熔斷狀態失敗（V9 未套用？）: {}", e.getMessage()); }
        Map<String, Map<String, Double>> peaks = new HashMap<>();
        try { if (posState != null) for (var r : posState.loadAll()) if ("PEAK".equals(r.kind())) peaks.computeIfAbsent(r.botId(), k -> new HashMap<>()).put(r.symbol(), r.peak()); }
        catch (Exception e) { log.warn("讀取最高價失敗: {}", e.getMessage()); }
        int n = 0;
        for (String id : running) {
            long since = startedAt.getOrDefault(id, 0L);
            String mode = "PAPER".equals(stageOf(id)) ? "PAPER" : "LIVE";
            double realized = 0; int consec = 0; double shadowReal = 0;
            if (trips != null) try {
                realized = trips.stats(id, mode, since).total();
                consec = trips.trailingLosses(id, mode, since);
                shadowReal = trips.stats(id, "SHADOW", since).total();
            } catch (Exception e) { log.debug("讀取來回紀錄失敗 {}: {}", id, e.getMessage()); }
            if (risk != null) risk.restore(id, pausedMap.get(id), realized, consec, peaks.get(id));
            if (shadow != null) shadow.restoreSession(id, shadowReal);
            n++;
        }
        log.info("重啟還原：{} 個模擬 / 影子部位、{} 個運行中機器人的熔斷 / 損益 / 最高價、{} 個熔斷中", pos, n,
                pausedMap.keySet().stream().filter(running::contains).count());
    }

    /** 設定階段。降到 PAPER 前先把真錢部位市價出清（否則切成模擬後沒人管）；離開 PAPER 丟掉模擬部位。 */
    public synchronized String setStage(String id, String to, String why) {
        if (!factories.containsKey(id)) return "no such strategy: " + id;
        to = to == null ? "" : to.toUpperCase();
        if (!STAGES.contains(to)) return "未知階段: " + to;
        String from = stageOf(id);
        if (from.equals(to)) return "stage unchanged: " + to;
        StrategyContextImpl c = contexts.get(id);
        if ("PAPER".equals(to) && c != null) for (var e : c.snapshot().entrySet()) if (Math.abs(e.getValue()) > 1e-12) c.exit(e.getKey(), "降級模擬盤");
        if ("PAPER".equals(from) && shadow != null) shadow.clearPaper(id);
        stage.put(id, to); stageSince.put(id, System.currentTimeMillis());
        if (repo != null) try { repo.setStage(id, to); } catch (Exception e) { log.warn("持久化失敗: {}", e.getMessage()); }
        if (c != null) c.setAllocation(1.0);
        if (risk != null) risk.reset(id);
        if (shadow != null) shadow.reset(id);
        boolean up = STAGES.indexOf(to) > STAGES.indexOf(from);
        log.warn("[{}] 晉升管線 {} → {}（{}）", id, from, to, why);
        notify((up ? ":arrow_up: 晉升 " : ":arrow_down: 降級 ") + "[" + names.getOrDefault(id, id) + "] " + label(from) + " → " + label(to) + "（" + why + "）");
        return "stage: " + id + " " + from + " -> " + to;
    }
    static String label(String st) { return switch (st) { case "PAPER" -> "模擬盤"; case "PILOT" -> "真錢試行"; default -> "正式運行"; }; }

    /** 每 10 分鐘評估關卡（自動模式）。 */
    @Scheduled(initialDelay = 180_000, fixedDelay = 600_000)
    public void pipelineTick() {
        if (!pipelineAuto) return;
        for (String id : new ArrayList<>(running)) {
            try {
                Map<String, Object> g = gates(id);
                Object act = g.get("action");
                if (act instanceof String a && !a.isBlank()) setStage(id, a, String.valueOf(g.get("reason")));
            } catch (Exception e) { log.warn("[{}] 關卡評估失敗: {}", id, e.getMessage()); }
        }
    }

    /** 目前階段的關卡進度：checks（每項 value / need / ok）、action（該晉升 / 降級到哪）、reason。 */
    public Map<String, Object> gates(String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        String st = stageOf(id); long since = stageSince.getOrDefault(id, System.currentTimeMillis());
        m.put("stage", st); m.put("since", since);
        List<Map<String, Object>> checks = new ArrayList<>();
        m.put("checks", checks);
        if (trips == null) return m;
        String paused = risk == null ? null : risk.pausedReason(id);
        switch (st) {
            case "PAPER" -> {
                var t = trips.stats(id, "PAPER", since);
                double hours = (System.currentTimeMillis() - since) / 3_600_000.0;
                checks.add(check("trades", t.n(), gate("paperMinTrades"), t.n() >= gate("paperMinTrades")));
                checks.add(check("hours", Math.round(hours * 10) / 10.0, gate("paperMinHours"), hours >= gate("paperMinHours")));
                checks.add(check("pf", r2(t.profitFactor()), gate("paperMinPF"), t.profitFactor() >= gate("paperMinPF")));
                checks.add(check("meanPct", r2(t.meanRet() * 100), 0, t.meanRet() > 0));
                if (checks.stream().allMatch(c -> (boolean) c.get("ok"))) { m.put("action", "PILOT"); m.put("reason", "模擬盤關卡全數通過"); }
                m.put("next", "PILOT");
            }
            case "PILOT" -> {
                var t = trips.stats(id, "LIVE", since);
                double[] sl = trips.slippage(id, since);
                checks.add(check("trades", t.n(), gate("pilotMinTrades"), t.n() >= gate("pilotMinTrades")));
                checks.add(check("pf", r2(t.profitFactor()), gate("pilotMinPF"), t.profitFactor() >= gate("pilotMinPF")));
                checks.add(check("meanPct", r2(t.meanRet() * 100), 0, t.meanRet() > 0));
                checks.add(check("slipBps", Math.round(sl[0] * 10) / 10.0, gate("pilotMaxSlipBps"), sl[1] < 3 || sl[0] <= gate("pilotMaxSlipBps")));
                m.put("next", "LIVE");
                if (paused != null) { m.put("action", "PAPER"); m.put("reason", "試行中熔斷：" + paused); }
                else if (t.n() >= gate("pilotMinTrades") && t.profitFactor() < 0.8) { m.put("action", "PAPER"); m.put("reason", String.format("試行 %d 筆獲利因子 %.2f < 0.8", t.n(), t.profitFactor())); }
                else if (checks.stream().allMatch(c -> (boolean) c.get("ok"))) { m.put("action", "LIVE"); m.put("reason", "真錢試行關卡全數通過"); }
            }
            default -> {
                var t = trips.recentStats(id, "LIVE", 10);
                checks.add(check("recentPF", r2(t.profitFactor()), gate("demotePF"), t.n() < 10 || t.profitFactor() >= gate("demotePF")));
                if (paused != null) { m.put("action", "PILOT"); m.put("reason", "熔斷：" + paused); }
                else if (t.n() >= 10 && t.profitFactor() < gate("demotePF")) { m.put("action", "PILOT"); m.put("reason", String.format("最近 10 筆獲利因子 %.2f < %.2f", t.profitFactor(), gate("demotePF"))); }
            }
        }
        return m;
    }
    private static Map<String, Object> check(String k, Object v, Object need, boolean ok) {
        Map<String, Object> c = new LinkedHashMap<>(); c.put("key", k); c.put("value", v); c.put("need", need); c.put("ok", ok); return c;
    }
    private static double r2(double v) { return Math.round(v * 100) / 100.0; }

    public Map<String, Object> pipelineStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("auto", pipelineAuto);
        Map<String, Double> g = new LinkedHashMap<>(); for (String k : GATES.keySet()) g.put(k, gate(k));
        m.put("gates", g);
        return m;
    }
    public Map<String, Object> setPipelineAuto(boolean on) {
        pipelineAuto = on;
        if (repo != null) try { repo.setSetting("pipeline_auto", String.valueOf(on)); } catch (Exception e) { log.warn("持久化失敗: {}", e.getMessage()); }
        return pipelineStatus();
    }

    // ── Thompson Sampling 自動配資 ──
    private BanditAllocator bandit;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBandit(BanditAllocator b) { this.bandit = b; }
    private volatile boolean banditEnabled = false;
    private volatile long banditRunAt = 0;
    private volatile String banditLast = "";
    static final double BANDIT_CAP = 2.5;

    /** 配資下限：小於這個倍率時單筆名目會低於幣安最低 5 USDT（每單名目 > 0 時），且至少保留 0.25 繼續探索。 */
    double banditFloor() {
        double f = orderNotional > 0 ? Math.max(0.25, 5.5 / orderNotional) : 0.25;
        return Math.min(f, BANDIT_CAP);
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = 600_000)
    public void banditTick() { if (banditEnabled) applyBandit("排程"); }

    public synchronized Map<String, Object> applyBandit(String by) {
        if (bandit == null) return banditStatus();
        List<String> ids = new ArrayList<>(running); ids.removeIf(id -> !"LIVE".equals(stageOf(id))); Collections.sort(ids);
        if (ids.isEmpty()) return banditStatus();
        Map<String, Double> alloc = bandit.allocations(ids, banditFloor(), BANDIT_CAP);
        StringBuilder sb = new StringBuilder();
        alloc.forEach((id, a) -> {
            StrategyContextImpl c = contexts.get(id);
            if (c == null) return;
            double old = c.getAllocation();
            c.setAllocation(a);
            if (repo != null) try { repo.setAllocation(id, a); } catch (Exception e) { log.warn("持久化失敗: {}", e.getMessage()); }
            if (Math.abs(old - a) >= 0.05) sb.append(String.format(" %s x%.2f→x%.2f", id, old, a));
        });
        banditRunAt = System.currentTimeMillis();
        banditLast = sb.length() == 0 ? "配資不變" : sb.toString().trim();
        log.info("Bandit 配資（{}）：{}", by, banditLast);
        if (sb.length() > 0) notify(":slot_machine: 自動配資調整（" + by + "）：" + sb);
        return banditStatus();
    }

    public Map<String, Object> banditStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", banditEnabled); m.put("lastRunAt", banditRunAt); m.put("last", banditLast);
        m.put("floor", banditFloor()); m.put("cap", BANDIT_CAP);
        List<Map<String, Object>> bots = new ArrayList<>();
        if (bandit != null) {
            List<String> ids = new ArrayList<>(running); ids.removeIf(id -> !"LIVE".equals(stageOf(id))); Collections.sort(ids);
            Map<String, Double> pb = bandit.pBest(ids);
            for (String id : ids) {
                Map<String, Object> r = new LinkedHashMap<>(bandit.stats(id));
                r.put("id", id); r.put("name", names.getOrDefault(id, id));
                r.put("pBest", Math.round(pb.getOrDefault(id, 0.0) * 1000) / 1000.0);
                r.put("allocation", contexts.get(id) == null ? 1.0 : contexts.get(id).getAllocation());
                bots.add(r);
            }
        }
        m.put("bots", bots);
        return m;
    }

    public Map<String, Object> setBanditEnabled(boolean on) {
        banditEnabled = on;
        if (repo != null) try { repo.setSetting("bandit_enabled", String.valueOf(on)); } catch (Exception e) { log.warn("持久化失敗: {}", e.getMessage()); }
        notify(on ? ":slot_machine: 自動配資（Thompson Sampling）已開啟" : ":slot_machine: 自動配資已關閉（配資倍率維持目前值）");
        return on ? applyBandit("開啟") : banditStatus();
    }

    /** 各標的實測滑價（回測成本校正用）。 */
    public Map<String, Map<String, Object>> slippageStats(int days) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (trips == null) return out;
        trips.slippageBySymbol(days).forEach((sym, v) -> out.put(sym, Map.of("bps", Math.round(v[0] * 100) / 100.0, "n", (int) v[1])));
        return out;
    }

    /** 模擬 / 影子 / 實盤對帳：近 days 天。 */
    public Map<String, Object> reconcile(String id, int days) {
        if (trips == null) return Map.of("error", "沒有資料庫");
        long since = System.currentTimeMillis() - days * 86_400_000L;
        Map<String, Object> out = new LinkedHashMap<>(Reconciliation.build(trips.tripRows(id, since), trips.slipRows(id, since),
                0.001, shadow == null ? 0 : shadow.rejects(id)));
        out.put("botId", id); out.put("days", days); out.put("stage", stageOf(id));
        out.put("stageSince", stageSince.getOrDefault(id, 0L));
        return out;
    }

    public Map<String, Map<String, Object>> regimes() { return risk == null ? Map.of() : risk.regimes(); }

    private AccountClient accountClient;   // 可為 null（單元測試）
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAccountClient(AccountClient c) { this.accountClient = c; }

    /**
     * 檢查「讓 id 以 allocation 運行」後，所有運行中機器人的合計曝險是否超標。
     * 曝險估算（保守）：每單名目 × allocation × 該機器人可交易的標的數（指定標的=1，ALL=目前看到的幣數）。
     * 每單名目=0 時改檢查 Σ allocation ≤ maxTotalAllocation。回 null = 通過，否則回原因。
     */
    String exposureViolation(String id, double allocationForId) {
        Set<String> ids = new java.util.HashSet<>(running); ids.add(id);
        double sumAlloc = 0, exposure = 0;
        for (String b : ids) {
            if (!b.equals(id) && "PAPER".equals(stageOf(b))) continue;   // 模擬盤不佔真錢曝險
            double a = b.equals(id) ? allocationForId : (contexts.get(b) == null ? 1.0 : contexts.get(b).getAllocation());
            sumAlloc += a;
            int syms = symbolFilter.containsKey(b) ? 1 : Math.max(1, prices.size());
            exposure += orderNotional * a * syms;
        }
        if (orderNotional <= 0) {
            if (maxTotalAllocation > 0 && sumAlloc > maxTotalAllocation)
                return String.format("運行中機器人配置倍率合計 %.2f 超過上限 %.2f（每單名目=0 時以此守門）", sumAlloc, maxTotalAllocation);
            return null;
        }
        if (maxExposurePct <= 0 || accountClient == null) return null;
        double equity;
        try {
            Object t = accountClient.balance().data().get("totalUsdt");
            equity = t instanceof Number n ? n.doubleValue() : 0;
        } catch (Exception e) { return "取不到帳戶權益，無法確認資金配置（account-service）: " + e.getMessage(); }
        if (equity <= 0) return "帳戶權益為 0，無法配置";
        if (exposure > equity * maxExposurePct)
            return String.format("運行中機器人最大曝險 %.0f USDT（每單名目 %.0f × 倍率 × 標的數）超過權益 %.0f × %.0f%%", exposure, orderNotional, equity, maxExposurePct * 100);
        return null;
    }

    private BotRepository repo;   // 可為 null（單元測試）
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBotRepository(BotRepository r) { this.repo = r; }
    private final Map<String, String> types = new ConcurrentHashMap<>();            // id -> type
    private final Map<String, Map<String, Object>> paramsOf = new ConcurrentHashMap<>();
    private final Set<String> builtins = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();            // id -> 本次上線時間(ms)
    private final Map<String, Map<String, Object>> pnlCache = new ConcurrentHashMap<>();
    // 診斷：每個機器人收到幾根 K 線 / 幾根已收盤、最後一根時間（確認行情真的有餵到策略）
    private final Map<String, long[]> barStats = new ConcurrentHashMap<>();   // id -> {bars, closedBars, lastBarAt}
    private volatile long pnlCachedAt = 0;

    public StrategyRunner(OrderClient orderClient, NotifyClient notifyClient, OmsClient omsClient, StrategyPositionBook book) {
        this.orderClient = orderClient;
        this.notifyClient = notifyClient;
        this.omsClient = omsClient;
        this.book = book;
        builtin("ma-fast", 3, 8, "雙均線 3/8（快）");
        builtin("ma-mid", 5, 20, "雙均線 5/20（中）");
        builtin("ma-slow", 10, 30, "雙均線 10/30（慢）");
    }

    private void builtin(String id, int fast, int slow, String name) {
        put(id, () -> new MovingAverageStrategy(id, fast, slow, 0.001), name, null);
        types.put(id, "ma"); builtins.add(id);
        paramsOf.put(id, new java.util.LinkedHashMap<>(Map.of("fast", fast, "slow", slow, "qty", 0.001)));
    }

    /**
     * 啟動後從 DB 讀回機器人（後續 6）：自建的重新建立、內建的套回參數 / 配置、RUNNING 的恢復運行。
     * 以前全在記憶體，重啟就歸零。
     */
    @jakarta.annotation.PostConstruct
    public void restoreFromDb() {
        if (repo == null) return;
        try {
            repo.setting("order_notional").ifPresent(v -> orderNotional = Math.max(0, Double.parseDouble(v)));
            repo.setting("bandit_enabled").ifPresent(v -> banditEnabled = Boolean.parseBoolean(v));
            repo.setting("pipeline_auto").ifPresent(v -> pipelineAuto = Boolean.parseBoolean(v));
            for (String k : GATES.keySet()) repo.setting("gate." + k).ifPresent(v -> { try { gateOverride.put(k, Double.parseDouble(v)); } catch (NumberFormatException ignore) {} });
            try { repo.stages().forEach((id, v) -> { stage.put(id, String.valueOf(v[0])); stageSince.put(id, (Long) v[1]); }); }
            catch (Exception e) { log.warn("讀取晉升階段失敗（V7 未套用？）: {}", e.getMessage()); }
            int restored = 0, resumed = 0;
            for (BotRepository.BotRow b : repo.findAll()) {
                if (!factories.containsKey(b.id())) {
                    String r = createBot(b.id(), b.type(), b.symbol(), b.params(), false);
                    if (!r.startsWith("created")) { log.warn("恢復機器人 {} 失敗: {}", b.id(), r); continue; }
                    restored++;
                } else if (builtins.contains(b.id()) && "ma".equals(b.type()) && b.params() != null && b.params().containsKey("fast")) {
                    applyMaParams(b.id(), (int) num(b.params(), "fast", 3), (int) num(b.params(), "slow", 8), num(b.params(), "qty", 0.001));
                }
                StrategyContextImpl c = contexts.get(b.id());
                if (c != null) c.setAllocation(b.allocation());
                if (b.running()) { running.add(b.id()); resumed++; startedAt.put(b.id(), b.startedAt() == null ? System.currentTimeMillis() : b.startedAt()); }
            }
            // 內建的若 DB 還沒有列，寫一筆讓之後的改動有地方存
            for (String id : builtins) if (repo.findAll().stream().noneMatch(r -> r.id().equals(id))) persist(id);
            log.info("機器人持久化：恢復 {} 個自建、{} 個恢復運行", restored, resumed);
            restoreRuntimeState();
            if (bandit != null && trips != null) for (String id : factories.keySet()) {
                try { for (double r : trips.recentReturns(id, List.of("LIVE", "PAPER"), BanditAllocator.WINDOW)) bandit.record(id, r); }
                catch (Exception e) { log.debug("配資學習資料讀取失敗: {}", e.getMessage()); }
            }
            if (resumed > 0) notify(":repeat: 重啟後恢復 " + resumed + " 個機器人運行：" + running);
        } catch (Exception e) {
            log.error("讀回機器人設定失敗（繼續以記憶體狀態運行）: {}", e.getMessage());
        }
    }

    private void persist(String id) {
        if (repo == null || !factories.containsKey(id)) return;
        try {
            repo.upsert(new BotRepository.BotRow(id, types.getOrDefault(id, "ma"), names.getOrDefault(id, id),
                    symbolFilter.get(id), paramsOf.getOrDefault(id, Map.of()),
                    contexts.get(id) == null ? 1.0 : contexts.get(id).getAllocation(), running.contains(id), builtins.contains(id), startedAt.get(id)));
        } catch (Exception e) { log.warn("機器人 {} 寫入 DB 失敗: {}", id, e.getMessage()); }
    }

    private void put(String id, Supplier<Strategy> factory, String name, String symbol) {
        factories.put(id, factory);
        instances.put(id, new ConcurrentHashMap<>()); twins.remove(id);
        contexts.putIfAbsent(id, new StrategyContextImpl(id, orderClient, prices, () -> orderNotional, book));
        if (risk != null) contexts.get(id).setBuyRouter(risk);
        if (shadow != null) contexts.get(id).setExecution(shadow);
        names.put(id, name);
        if (symbol != null) symbolFilter.put(id, symbol.toUpperCase());
    }

    private Strategy instanceFor(String id, String symbol) {
        return instances.computeIfAbsent(id, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(symbol, k -> factories.get(id).get());
    }

    // ── 啟動對帳：部位簿來自 OMS，不是記憶體 ──
    @Scheduled(initialDelay = 5_000, fixedDelay = 60_000)
    public void reconcileFromOms() {
        try {
            var resp = omsClient.strategyPositions();
            if (resp != null && resp.code() == 0 && resp.data() != null) {
                book.reconcile(resp.data());
            } else {
                log.warn("OMS 對帳回應異常: {}", resp == null ? "null" : resp.message());
            }
        } catch (Exception e) {
            log.warn("OMS 對帳失敗（策略將暫停下單直到成功）: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = Topics.MARKET_KLINE, groupId = "strategy-service")
    public void onMarketData(MarketDataEvent event) {
        if (event == null || event.kline() == null) return;
        Kline k = event.kline();
        String sym = k.symbol();
        String iv = k.interval() == null || k.interval().isBlank() ? "1m" : k.interval();
        boolean tick = "1m".equals(iv);   // 1 分 K（含未收盤）當即時報價：出場檢查、現價
        if (tick) prices.put(sym, k.close());
        if (risk != null) risk.onKline(k);
        for (String id : running) {
            String filter = symbolFilter.get(id);
            if (filter != null && !filter.equals(sym)) continue;   // 機器人限定 symbol
            if (!factories.containsKey(id)) continue;
            if (tick) {
                if (shadow != null && !"PAPER".equals(stageOf(id))) shadow.onTick(id, k);
                if (risk != null) try { risk.checkExits(id, k, contexts.get(id)); }
                                  catch (Exception e) { log.warn("[{}] 出場檢查失敗 {}: {}", id, sym, e.getMessage()); }
            }
            if (!iv.equals(intervalOf(id))) continue;               // 策略只吃自己週期的 K 線
            long[] st = barStats.computeIfAbsent(id, x -> new long[3]);
            st[0]++; if (k.closed()) st[1]++; st[2] = System.currentTimeMillis();
            try {
                Strategy inst = instanceFor(id, sym);
                Long warmedTo = warmed(id, sym, iv, inst, k.openTime(), false);
                if (warmedTo != null && k.openTime() <= warmedTo) continue;   // 歷史已經餵過這根
                inst.onBar(k, contexts.get(id));
            }
            catch (Exception e) { log.warn("策略 {} [{}] 執行錯誤: {}", id, sym, e.getMessage()); }
            if (shorts(id)) try {
                Kline inv = invert(k);
                if (inv == null) continue;
                Strategy tw = twinFor(id, sym);
                Long warmedTo = warmed(id, sym, iv, tw, k.openTime(), true);
                if (warmedTo != null && k.openTime() <= warmedTo) continue;
                tw.onBar(inv, shortView(id));
            } catch (Exception e) { log.warn("策略 {} [{}] 空單分身執行錯誤: {}", id, sym, e.getMessage()); }
        }
    }

    /** 機器人市場：SPOT（只做多）| USDT_M（U 本位合約，可做空）。 */
    public String marketOf(String id) {
        Object v = paramsOf.getOrDefault(id, Map.of()).get("market");
        return v != null && "USDT_M".equalsIgnoreCase(String.valueOf(v)) ? "USDT_M" : "SPOT";
    }
    private void applyMarket(String id) { StrategyContextImpl c = contexts.get(id); if (c != null) c.setMarket(marketOf(id)); }
    private boolean shorts(String id) { return "USDT_M".equals(marketOf(id)) && (risk == null || risk.p(id, "allowShort") > 0); }

    // ── 做空：反向價格分身 ──
    // 合約機器人每個標的多跑一份策略，餵「價格倒數」的 K 線（1/p，高低互換）。分身在倒數世界「買進」= 真實世界價格在跌 → 開空；
    // 分身「賣出」→ 回補。不用改任何一支策略，30+ 種策略都能做空；停損 / 停利由風控以空單規則（鏡像）處理。
    private final Map<String, Map<String, Strategy>> twins = new ConcurrentHashMap<>();
    private Strategy twinFor(String id, String symbol) {
        return twins.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).computeIfAbsent(symbol, k -> factories.get(id).get());
    }
    static Kline invert(Kline k) {
        if (k.open() <= 0 || k.high() <= 0 || k.low() <= 0 || k.close() <= 0) return null;
        return new Kline(k.symbol(), k.interval(), k.openTime(), 1 / k.open(), 1 / k.low(), 1 / k.high(), 1 / k.close(),
                k.volume(), k.closeTime(), k.closed());
    }
    /** 分身看到的世界：部位 = 空單大小，價格 = 倒數；它的 BUY / SELL 翻成真實的開空 / 回補。 */
    private StrategyContext shortView(String id) {
        StrategyContextImpl real = contexts.get(id);
        return new StrategyContext() {
            @Override public void submit(OrderIntent i) {
                if (real == null) return;
                double net = real.position(i.symbol());
                if (i.side() == OrderSide.BUY) {   // 開空（若手上是多單，反向訊號先平多）
                    real.submit(new OrderIntent(i.clientOrderId(), id, i.symbol(), OrderSide.SELL, OrderType.MARKET, null,
                            i.quantity(), false, "USDT_M", i.accountOrDefault()));
                } else if (net < -1e-12) {          // 回補空單
                    real.submit(new OrderIntent(i.clientOrderId(), id, i.symbol(), OrderSide.BUY, OrderType.MARKET, null,
                            Math.abs(net), true, "USDT_M", i.accountOrDefault()));
                }
            }
            @Override public double position(String symbol) { return real == null ? 0 : Math.max(0, -real.position(symbol)); }
            @Override public double price(String symbol) { double p = prices.getOrDefault(symbol, 0.0); return p > 0 ? 1 / p : 0; }
        };
    }

    /** 機器人的 K 線週期（params.interval，預設 1m）。 */
    public String intervalOf(String id) {
        Object v = paramsOf.getOrDefault(id, Map.of()).get("interval");
        String s = v == null ? "" : String.valueOf(v).trim();
        return s.isEmpty() ? "1m" : s;
    }

    // ── 歷史暖機：新的策略實例先吃一段歷史 K 線，不必等真實 K 線慢慢累積（1h 機器人原本要等 30 小時） ──
    static final int WARM_BARS = 200;
    private MarketClient market;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMarket(MarketClient m) {
        this.market = m;
        if (risk != null) risk.setHistory(this::history);
    }
    /** 實例 -> 暖機餵到的最後一根 openTime（實例被換掉就自然重暖） */
    private final Map<Strategy, Long> warmedAt = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<String, Integer> warmedCount = new ConcurrentHashMap<>();

    List<Kline> history(String symbol, String interval) {
        if (market == null) return List.of();
        var r = market.klines(symbol, interval, WARM_BARS + 1);
        return r != null && r.code() == 0 && r.data() != null ? r.data() : List.of();
    }

    /** 第一次遇到這個實例：用歷史 K 線餵一遍（下單全部吞掉）。回傳餵到的最後一根 openTime；沒暖機回 null。 */
    private Long warmed(String id, String sym, String iv, Strategy inst, long beforeOpenTime, boolean invert) {
        Long t = warmedAt.get(inst);
        if (t != null) return t;
        long last = -1;
        try {
            StrategyContextImpl real = contexts.get(id);
            StrategyContext muted = new StrategyContext() {
                @Override public void submit(OrderIntent intent) { /* 暖機期間不下單 */ }
                @Override public double position(String symbol) { return real == null ? 0 : real.position(symbol); }
                @Override public double price(String symbol) { return prices.getOrDefault(symbol, 0.0); }
            };
            for (Kline b : history(sym, iv)) {
                if (!b.closed() || b.openTime() >= beforeOpenTime) continue;
                Kline bar = invert ? invert(b) : b;
                if (bar == null) continue;
                inst.onBar(bar, muted); last = b.openTime();
            }
            if (last > 0 && !invert) warmedCount.merge(id, 1, Integer::sum);
        } catch (Exception e) { log.debug("[{}] {} {} 歷史暖機失敗（改用即時 K 線慢慢暖）: {}", id, sym, iv, e.getMessage()); }
        warmedAt.put(inst, last);
        return last;
    }

    /** 各機器人損益（本次上線以來），向 OMS 查，5 秒快取；OMS 不在就空。 */
    private Map<String, Map<String, Object>> pnlAll() {
        long now = System.currentTimeMillis();
        if (now - pnlCachedAt < 5000) return pnlCache;
        Map<String, Map<String, Object>> fresh = new ConcurrentHashMap<>();
        for (String id : running) {
            try {
                var r = omsClient.strategyPnl(id, startedAt.getOrDefault(id, 0L));
                if (r != null && r.code() == 0 && r.data() != null) fresh.put(id, r.data());
            } catch (Exception e) { log.debug("查 {} 損益失敗: {}", id, e.getMessage()); }
        }
        pnlCache.clear(); pnlCache.putAll(fresh); pnlCachedAt = now;
        return pnlCache;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Map<String, Object>> pnl = pnlAll();
        long now = System.currentTimeMillis();
        factories.forEach((id, s) -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", id); m.put("name", names.getOrDefault(id, id));
            m.put("status", running.contains(id) ? "RUNNING" : "STOPPED");
            m.put("position", contexts.get(id).totalPosition());
            m.put("allocation", contexts.get(id).getAllocation());
            m.put("symbol", symbolFilter.getOrDefault(id, "ALL"));
            m.put("type", types.getOrDefault(id, "ma"));
            m.put("family", BotRiskManager.family(types.getOrDefault(id, "ma")));
            m.put("params", paramsOf.getOrDefault(id, Map.of()));
            m.put("builtin", builtins.contains(id));
            Long st = startedAt.get(id);
            m.put("startedAt", st == null ? 0 : st);
            m.put("runningSec", st == null ? 0 : (now - st) / 1000);
            m.put("pnl", pnl.get(id));   // null = 未運行 / OMS 不在
            long[] bs = barStats.getOrDefault(id, new long[3]);
            StrategyContextImpl c = contexts.get(id);
            Map<String, Object> diag = new java.util.LinkedHashMap<>();
            diag.put("bars", bs[0]); diag.put("closedBars", bs[1]); diag.put("lastBarAt", bs[2]);
            diag.put("symbols", symbolFilter.containsKey(id) ? 1 : prices.size());
            diag.put("interval", intervalOf(id));
            m.put("market", marketOf(id));
            diag.put("warmed", warmedCount.getOrDefault(id, 0));
            diag.put("signals", c.signals()); diag.put("lastSignalAt", c.lastSignalAt());
            diag.put("lastSignal", c.lastSignal()); diag.put("lastOutcome", c.lastOutcome());
            m.put("diag", diag);
            m.put("stage", stageOf(id));
            m.put("stageSince", stageSince.getOrDefault(id, 0L));
            if (running.contains(id)) {
                try { m.put("gates", gates(id)); } catch (Exception e) { log.debug("gates: {}", e.getMessage()); }
                if (shadow != null && !"PAPER".equals(stageOf(id)) && risk != null) {
                    double live = risk.sessionPnl(id);
                    m.put("shadow", shadow.compare(id, live));
                    shadow.alertGap(id, live);
                }
            }
            if (risk != null) {
                Map<String, Object> rk = new java.util.LinkedHashMap<>();
                rk.put("paused", risk.pausedReason(id));
                rk.put("consecLosses", risk.consecLosses(id));
                rk.put("sessionPnl", Math.round(risk.sessionPnl(id) * 10000) / 10000.0);
                rk.put("openSlots", contexts.get(id).openSlots());
                Map<String, Object> eff = new java.util.LinkedHashMap<>();
                for (String k : BotRiskManager.DEFAULTS.keySet()) eff.put(k, risk.p(id, k));
                rk.put("params", eff);
                rk.put("exits", risk.exitLevels(id));
                m.put("risk", rk);
            }
            out.add(m);
        });
        out.sort(Comparator.comparing(m -> (String) m.get("id")));
        return out;
    }

    public String start(String id) {
        if (!factories.containsKey(id)) return "no such strategy: " + id;
        String bad = "PAPER".equals(stageOf(id)) ? null : exposureViolation(id, contexts.get(id) == null ? 1.0 : contexts.get(id).getAllocation());
        if (bad != null) { log.warn("拒絕上線 {}: {}", id, bad); return "rejected: " + bad; }
        running.add(id); startedAt.put(id, System.currentTimeMillis()); warmedCount.remove(id); log.info("機器人上線: {}", id);
        if (risk != null) risk.reset(id);
        if (shadow != null) shadow.reset(id);
        if (repo != null) try { repo.setRunning(id, true); } catch (Exception e) { log.warn("持久化失敗: {}", e.getMessage()); }
        notify(":arrow_forward: 機器人上線 [" + names.getOrDefault(id, id) + "] (" + id
                + ") 標的 " + symbolFilter.getOrDefault(id, "ALL"));
        return "started: " + id;
    }
    /** 熔斷後恢復進場（清空連虧與本次損益統計）。 */
    public String resume(String id) {
        if (!factories.containsKey(id)) return "no such strategy: " + id;
        if (risk != null) risk.reset(id);
        notify(":arrow_forward: 機器人恢復進場 [" + names.getOrDefault(id, id) + "] (" + id + ")");
        return "resumed: " + id;
    }

    public String stop(String id) {
        running.remove(id); startedAt.remove(id); barStats.remove(id); if (risk != null) risk.forget(id); instances.put(id, new ConcurrentHashMap<>()); twins.remove(id); log.info("機器人下線: {}", id);
        if (repo != null) try { repo.setRunning(id, false); } catch (Exception e) { log.warn("持久化失敗: {}", e.getMessage()); }
        notify(":pause_button: 機器人下線 [" + names.getOrDefault(id, id) + "] (" + id + ")");
        return "stopped: " + id;
    }

    /** 發 Slack 通知，失敗不影響策略運行。 */
    private void notify(String text) {
        try { notifyClient.send(Map.of("text", text)); }
        catch (Exception e) { log.debug("通知發送失敗（忽略）: {}", e.getMessage()); }
    }

    /** 每單目標名目(USDT)：0=用策略原始固定數量。執行期可調(風控頁)。 */
    public double getOrderNotional() { return orderNotional; }
    public double setOrderNotional(double v) {
        orderNotional = Math.max(0, v);
        if (repo != null) try { repo.setSetting("order_notional", String.valueOf(orderNotional)); } catch (Exception e) { log.warn("持久化失敗: {}", e.getMessage()); }
        return orderNotional;
    }

    /** 資金配置：設定策略下單量倍率（見架構文件 §10 資金配置）。 */
    public String setAllocation(String id, double weight) {
        StrategyContextImpl c = contexts.get(id);
        if (c == null) return "no such strategy: " + id;
        if (running.contains(id)) { String bad = exposureViolation(id, weight); if (bad != null) return "rejected: " + bad; }
        c.setAllocation(weight);
        if (repo != null) try { repo.setAllocation(id, weight); } catch (Exception e) { log.warn("持久化失敗: {}", e.getMessage()); }
        return "allocation set: " + id + " x" + weight;
    }

    public String updateParams(String id, int fast, int slow, double qty) {
        Supplier<Strategy> f = factories.get(id);
        if (f == null || !(f.get() instanceof MovingAverageStrategy)) return "僅雙均線支援此參數: " + id;
        applyMaParams(id, fast, slow, qty);
        if (repo != null) try { repo.setParams(id, paramsOf.get(id)); } catch (Exception e) { log.warn("持久化失敗: {}", e.getMessage()); }
        log.info("機器人 {} 參數更新 fast={} slow={} qty={}", id, fast, slow, qty);
        return "updated: " + id;
    }

    private void applyMaParams(String id, int fast, int slow, double qty) {
        factories.put(id, () -> new MovingAverageStrategy(id, fast, slow, qty));
        instances.put(id, new ConcurrentHashMap<>()); twins.remove(id);   // 丟掉舊實例，各 symbol 重新暖機
        paramsOf.put(id, new java.util.LinkedHashMap<>(Map.of("fast", fast, "slow", slow, "qty", qty)));
    }

    /** 動態建立機器人（見架構文件 §8 策略打包精神）。type: ma|rsi|grid。 */
    public String createBot(String id, String type, String symbol, Map<String, Object> p) { return createBot(id, type, symbol, p, true); }

    /** 依類型與參數建工廠；未知類型回 null。 */
    record Built(Supplier<Strategy> factory, String name) {}

    Built build(String id, String type, Map<String, Object> p) {
        double qty = num(p, "qty", 0.001);
        Supplier<Strategy> s;
        String name;
        switch (type == null ? "" : type.toLowerCase()) {
            case "rsi" -> {
                s = () -> new RsiStrategy(id, (int) num(p, "window", 14), num(p, "oversold", 30),
                        num(p, "overbought", 70), qty);
                name = "RSI 機器人";
            }
            case "grid" -> {
                s = () -> new GridStrategy(id, num(p, "gridPct", 0.005), qty);
                name = "網格機器人";
            }
            case "cci" -> { s = () -> new CciStrategy(id, (int) num(p, "period", 20), qty); name = "CCI 機器人"; }
            case "williams" -> { s = () -> new WilliamsRStrategy(id, (int) num(p, "period", 14), qty); name = "威廉%R 機器人"; }
            case "donchian" -> { s = () -> new DonchianStrategy(id, (int) num(p, "period", 20), qty); name = "唐奇安突破機器人"; }
            case "momentum" -> { s = () -> new MomentumStrategy(id, (int) num(p, "period", 10), num(p, "threshold", 0.002), qty); name = "動量機器人"; }
            case "atr" -> { s = () -> new AtrChannelStrategy(id, (int) num(p, "period", 14), num(p, "mult", 2), qty); name = "ATR 通道機器人"; }
            case "supertrend" -> { s = () -> new SuperTrendStrategy(id, (int) num(p, "period", 10), num(p, "mult", 3), qty); name = "SuperTrend 機器人"; }
            case "ichimoku" -> { s = () -> new IchimokuStrategy(id, qty); name = "一目均衡機器人"; }
            case "sar" -> { s = () -> new SarStrategy(id, num(p, "step", 0.02), num(p, "max", 0.2), qty); name = "SAR 機器人"; }
            case "vwap" -> { s = () -> new VwapStrategy(id, (int) num(p, "period", 20), num(p, "dev", 0.003), qty); name = "VWAP 機器人"; }
            case "stochrsi" -> { s = () -> new StochRsiStrategy(id, (int) num(p, "period", 14), qty); name = "StochRSI 機器人"; }
            case "trix" -> { s = () -> new TrixStrategy(id, (int) num(p, "period", 15), qty); name = "TRIX 機器人"; }
            case "adx" -> { s = () -> new AdxStrategy(id, (int) num(p, "period", 14), qty); name = "ADX/DMI 機器人"; }
            case "keltner" -> { s = () -> new KeltnerStrategy(id, (int) num(p, "period", 20), num(p, "mult", 2), qty); name = "肯特納通道機器人"; }
            case "bollbreak" -> { s = () -> new BollBreakStrategy(id, (int) num(p, "period", 20), num(p, "mult", 2), qty); name = "布林突破機器人"; }
            case "dualthrust" -> { s = () -> new DualThrustStrategy(id, (int) num(p, "period", 5), num(p, "k", 0.5), qty); name = "Dual Thrust 機器人"; }
            case "roc" -> { s = () -> new RocStrategy(id, (int) num(p, "period", 12), num(p, "threshold", 0.003), qty); name = "ROC 機器人"; }
            case "aroon" -> { s = () -> new AroonStrategy(id, (int) num(p, "period", 25), qty); name = "Aroon 機器人"; }
            case "cmf" -> { s = () -> new CmfStrategy(id, (int) num(p, "period", 20), qty); name = "CMF 機器人"; }
            case "zscore" -> { s = () -> new ZScoreStrategy(id, (int) num(p, "period", 20), num(p, "threshold", 2), qty); name = "Z分數機器人"; }
            case "volbreak" -> { s = () -> new VolBreakStrategy(id, (int) num(p, "period", 20), num(p, "mult", 2), qty); name = "量能突破機器人"; }
            case "tema" -> { s = () -> new TemaStrategy(id, (int) num(p, "fast", 10), (int) num(p, "slow", 30), qty); name = "TEMA 機器人"; }
            case "heikin" -> { s = () -> new HeikinAshiStrategy(id, qty); name = "Heikin-Ashi 機器人"; }
            case "wma" -> { s = () -> new WmaStrategy(id, (int) num(p, "fast", 10), (int) num(p, "slow", 30), qty); name = "WMA 機器人"; }
            case "ema" -> {
                s = () -> new EmaStrategy(id, (int) num(p, "fast", 12), (int) num(p, "slow", 26), qty);
                name = "EMA 交叉機器人";
            }
            case "macd" -> {
                s = () -> new MacdStrategy(id, (int) num(p, "fast", 12), (int) num(p, "slow", 26),
                        (int) num(p, "signal", 9), qty);
                name = "MACD 機器人";
            }
            case "boll" -> {
                s = () -> new BollStrategy(id, (int) num(p, "period", 20), num(p, "mult", 2), qty);
                name = "布林通道機器人";
            }
            case "kdj" -> {
                s = () -> new KdjStrategy(id, (int) num(p, "period", 9), qty);
                name = "KDJ 機器人";
            }
            case "ma" -> {
                s = () -> new MovingAverageStrategy(id, (int) num(p, "fast", 3), (int) num(p, "slow", 8), qty);
                name = "雙均線機器人";
            }
            default -> { return null; }
        }
        return new Built(s, name);
    }


    public String createBot(String id, String type, String symbol, Map<String, Object> p, boolean persist) {
        if (factories.containsKey(id)) return "id 已存在: " + id;
        Built b = build(id, type, p);
        if (b == null) return "未知類型: " + type;
        Supplier<Strategy> s = b.factory(); String name = b.name();
        put(id, s, name + (symbol != null ? " · " + symbol.toUpperCase() : ""), symbol);
        types.put(id, type.toLowerCase());
        paramsOf.put(id, p == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(p));
        applyMarket(id);
        if (persist) {
            persist(id);
            // 新機器人從模擬盤開始，過關才用真錢（p.stage 可指定，例如 LIVE 跳過）
            String st = p != null && p.get("stage") instanceof String x && STAGES.contains(x.toUpperCase()) ? x.toUpperCase() : "PAPER";
            stage.put(id, st); stageSince.put(id, System.currentTimeMillis());
            if (repo != null) try { repo.setStage(id, st); } catch (Exception e) { log.warn("持久化失敗: {}", e.getMessage()); }
        }
        log.info("建立機器人 {} type={} symbol={}", id, type, symbol);
        if (persist) notify(":new: 建立機器人 [" + name + "] (" + id + ") 標的 "
                + (symbol != null ? symbol.toUpperCase() : "ALL"));
        return "created: " + id;
    }

    /** 任一類型改參數（後續 4）：與既有參數合併後重建工廠、丟掉實例重新暖機、持久化。運行中也可改。 */
    public String updateParamsAny(String id, Map<String, Object> patch) {
        if (!factories.containsKey(id)) return "no such strategy: " + id;
        String type = types.getOrDefault(id, "ma");
        Map<String, Object> merged = new java.util.LinkedHashMap<>(paramsOf.getOrDefault(id, Map.of()));
        if (patch != null) patch.forEach((k, v) -> { if (v != null) merged.put(k, v); });
        if (patch != null && !patch.isEmpty() && patch.keySet().stream().allMatch(BotRiskManager::isRiskKey)) {
            // 只改風控參數：不重建策略、不重新暖機
            paramsOf.put(id, merged); applyMarket(id);
            if (repo != null) try { repo.setParams(id, merged); } catch (Exception e) { log.warn("持久化失敗: {}", e.getMessage()); }
            log.info("機器人 {} 風控參數更新 {}", id, patch);
            return "updated: " + id + " " + merged;
        }
        Built b = build(id, type, merged);
        if (b == null) return "未知類型: " + type;
        factories.put(id, b.factory());
        instances.put(id, new ConcurrentHashMap<>()); twins.remove(id);
        paramsOf.put(id, merged); applyMarket(id);
        if (repo != null) try { repo.setParams(id, merged); } catch (Exception e) { log.warn("持久化失敗: {}", e.getMessage()); }
        log.info("機器人 {} 參數更新 {}", id, merged);
        return "updated: " + id + " " + merged;
    }

    public Map<String, Object> paramsOf(String id) { return paramsOf.getOrDefault(id, Map.of()); }

    /** 刪除機器人：先下線（可選平倉）；內建的不能刪。 */
    public String deleteBot(String id, boolean flatten) {
        if (!factories.containsKey(id)) return "no such strategy: " + id;
        if (builtins.contains(id)) return "內建策略不可刪除: " + id;
        if (running.contains(id)) stop(id, flatten);
        factories.remove(id); instances.remove(id); twins.remove(id); contexts.remove(id); names.remove(id);
        symbolFilter.remove(id); types.remove(id); paramsOf.remove(id); startedAt.remove(id); stage.remove(id); stageSince.remove(id);
        if (shadow != null) shadow.clearPaper(id);
        if (posState != null) try { posState.deleteBot(id); } catch (Exception e) { log.debug("清除部位狀態失敗: {}", e.getMessage()); }
        if (repo != null) try { repo.delete(id); } catch (Exception e) { log.warn("刪除持久化失敗: {}", e.getMessage()); }
        notify(":wastebasket: 刪除機器人 (" + id + ")");
        return "deleted: " + id;
    }

    /** 下線並（可選）平倉：對策略帳上每個有多頭部位的標的送市價 SELL。 */
    public String stop(String id, boolean flatten) {
        StrategyContextImpl pc = contexts.get(id);
        if (flatten && pc != null && (pc.isPaper() || pc.futures())) {   // 模擬盤 / 合約（含空單）都走 context 平倉
            for (var e : pc.snapshot().entrySet()) if (Math.abs(e.getValue()) > 1e-12) pc.exit(e.getKey(), "下線平倉");
            return stop(id) + ", paper flattened";
        }
        String r = stop(id);
        if (!flatten) return r;
        int sent = 0;
        for (var e : book.snapshot(id).entrySet()) {
            double qty = e.getValue();
            if (qty <= 1e-12) continue;   // 只處理多頭（現貨）
            try {
                var resp = orderClient.submit(new OrderIntent("flat-" + id + "-" + System.currentTimeMillis(), id, e.getKey(),
                        OrderSide.SELL, OrderType.MARKET, null, qty, false, "SPOT", "main"));
                if (resp != null && resp.code() == 0) sent++;
                else log.warn("平倉被拒 {} {}: {}", id, e.getKey(), resp == null ? "null" : resp.message());
            } catch (Exception ex) { log.warn("平倉送單失敗 {} {}: {}", id, e.getKey(), ex.getMessage()); }
        }
        notify(":outbox_tray: 機器人下線並平倉 (" + id + ")，送出 " + sent + " 張市價賣單");
        return r + ", flatten sent=" + sent;
    }

    private static double num(Map<String, Object> p, String k, double def) {
        Object v = p == null ? null : p.get(k);
        return v == null ? def : ((Number) v).doubleValue();
    }
}
