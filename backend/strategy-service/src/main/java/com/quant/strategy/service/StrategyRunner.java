package com.quant.strategy.service;

import com.quant.common.event.MarketDataEvent;
import com.quant.common.event.Topics;
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

    public StrategyRunner(OrderClient orderClient, NotifyClient notifyClient, OmsClient omsClient, StrategyPositionBook book) {
        this.orderClient = orderClient;
        this.notifyClient = notifyClient;
        this.omsClient = omsClient;
        this.book = book;
        put("ma-fast", () -> new MovingAverageStrategy("ma-fast", 3, 8, 0.001), "雙均線 3/8（快）", null);
        put("ma-mid",  () -> new MovingAverageStrategy("ma-mid", 5, 20, 0.001), "雙均線 5/20（中）", null);
        put("ma-slow", () -> new MovingAverageStrategy("ma-slow", 10, 30, 0.001), "雙均線 10/30（慢）", null);
    }

    private void put(String id, Supplier<Strategy> factory, String name, String symbol) {
        factories.put(id, factory);
        instances.put(id, new ConcurrentHashMap<>());
        contexts.putIfAbsent(id, new StrategyContextImpl(id, orderClient, prices, () -> orderNotional, book));
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
        String sym = event.kline().symbol();
        prices.put(sym, event.kline().close());
        for (String id : running) {
            String filter = symbolFilter.get(id);
            if (filter != null && !filter.equals(sym)) continue;   // 機器人限定 symbol
            if (!factories.containsKey(id)) continue;
            try { instanceFor(id, sym).onBar(event.kline(), contexts.get(id)); }
            catch (Exception e) { log.warn("策略 {} [{}] 執行錯誤: {}", id, sym, e.getMessage()); }
        }
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        factories.forEach((id, s) -> out.add(Map.of(
                "id", id, "name", names.getOrDefault(id, id),
                "status", running.contains(id) ? "RUNNING" : "STOPPED",
                "position", contexts.get(id).totalPosition(),
                "allocation", contexts.get(id).getAllocation(),
                "symbol", symbolFilter.getOrDefault(id, "ALL"))));
        out.sort(Comparator.comparing(m -> (String) m.get("id")));
        return out;
    }

    public String start(String id) {
        if (!factories.containsKey(id)) return "no such strategy: " + id;
        running.add(id); log.info("機器人上線: {}", id);
        notify(":arrow_forward: 機器人上線 [" + names.getOrDefault(id, id) + "] (" + id
                + ") 標的 " + symbolFilter.getOrDefault(id, "ALL"));
        return "started: " + id;
    }
    public String stop(String id) {
        running.remove(id); log.info("機器人下線: {}", id);
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
    public double setOrderNotional(double v) { orderNotional = Math.max(0, v); return orderNotional; }

    /** 資金配置：設定策略下單量倍率（見架構文件 §10 資金配置）。 */
    public String setAllocation(String id, double weight) {
        StrategyContextImpl c = contexts.get(id);
        if (c == null) return "no such strategy: " + id;
        c.setAllocation(weight);
        return "allocation set: " + id + " x" + weight;
    }

    public String updateParams(String id, int fast, int slow, double qty) {
        Supplier<Strategy> f = factories.get(id);
        if (f == null || !(f.get() instanceof MovingAverageStrategy)) return "僅雙均線支援此參數: " + id;
        factories.put(id, () -> new MovingAverageStrategy(id, fast, slow, qty));
        instances.put(id, new ConcurrentHashMap<>());   // 丟掉舊實例，各 symbol 重新暖機
        log.info("機器人 {} 參數更新 fast={} slow={} qty={}", id, fast, slow, qty);
        return "updated: " + id;
    }

    /** 動態建立機器人（見架構文件 §8 策略打包精神）。type: ma|rsi|grid。 */
    public String createBot(String id, String type, String symbol, Map<String, Object> p) {
        if (factories.containsKey(id)) return "id 已存在: " + id;
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
            default -> { return "未知類型: " + type + "（支援 ma/ema/macd/boll/kdj/rsi/grid/cci/williams/donchian/momentum/atr）"; }
        }
        put(id, s, name + (symbol != null ? " · " + symbol.toUpperCase() : ""), symbol);
        log.info("建立機器人 {} type={} symbol={}", id, type, symbol);
        notify(":new: 建立機器人 [" + name + "] (" + id + ") 標的 "
                + (symbol != null ? symbol.toUpperCase() : "ALL"));
        return "created: " + id;
    }

    private static double num(Map<String, Object> p, String k, double def) {
        Object v = p == null ? null : p.get(k);
        return v == null ? def : ((Number) v).doubleValue();
    }
}
