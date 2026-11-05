package com.quant.strategy.core;

import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.strategy.StrategyContext;
import com.quant.strategy.position.StrategyPositionBook;
import com.quant.strategy.service.OrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * 單策略上下文（多策略並行各持一份）。下單量依「資金配置倍率」縮放，實現實盤資金配置。
 * 若設定 order.notional-usdt(&gt;0)，改以「名目金額」下單：qty = 名目 / 現價，
 * 交易所 filter 對齊(stepSize/minNotional)由 order-execution 端處理。
 *
 * 部位不再在本地樂觀更新：只有 OMS 的成交事件能改變部位（見 StrategyPositionBook）。
 * 同一 (策略, symbol) 有未結單時拒絕再送，避免 UNKNOWN / 慢成交期間重複開倉。
 *
 * 模擬盤（晉升管線 PAPER 階段）：{@link Execution#paper} 為 true 時不送 OMS，由影子引擎以現價成交，部位也看影子引擎。
 */
public class StrategyContextImpl implements StrategyContext {

    private static final Logger log = LoggerFactory.getLogger(StrategyContextImpl.class);
    static final double MIN_ORDER_USDT = 5.5;   // 幣安最低 5 USDT，留一點緩衝
    private final String strategyId;
    private final OrderClient orderClient;
    private final Map<String, Double> sharedPrices;
    private final DoubleSupplier orderNotional;   // 每單目標名目(USDT)，0=用策略原始數量
    private final StrategyPositionBook book;
    private volatile double allocation = 1.0;   // 資金配置倍率
    // 診斷：策略產生了幾次訊號、最後一次是什麼、結果如何（UI「為什麼沒下單」看這裡）
    private final java.util.concurrent.atomic.AtomicLong signals = new java.util.concurrent.atomic.AtomicLong();
    private volatile long lastSignalAt = 0;
    private volatile String lastSignal = "";
    private volatile String lastOutcome = "";
    public long signals() { return signals.get(); }
    public long lastSignalAt() { return lastSignalAt; }
    public String lastSignal() { return lastSignal; }
    public String lastOutcome() { return lastOutcome; }

    /** 記下一次決策結果（UI 診斷 + 決策日誌）。 */
    public void decide(String symbol, OrderSide side, String outcome) {
        lastOutcome = outcome;
        Execution x = exec;
        if (x != null) try { x.onDecision(strategyId, symbol, side, price(symbol), outcome); } catch (Exception ignore) {}
    }
    public void markOutcome(String o) { lastOutcome = o; }

    /** 進場路由：BUY 先交給它（暫停 / 冷卻 / 同時持倉上限 + 排名），回 true 代表它接手、稍後呼叫 {@link #execute}。null = 直接送（單元測試）。 */
    public interface BuyRouter { boolean route(StrategyContextImpl ctx, OrderIntent intent); }
    private volatile BuyRouter buyRouter;
    public void setBuyRouter(BuyRouter r) { this.buyRouter = r; }

    /** 執行掛鉤：模擬盤成交、影子雙胞胎鏡像、波動率下單量、決策日誌。null = 純實盤（單元測試）。 */
    public interface Execution {
        boolean paper(String strategyId);
        void paperFill(String strategyId, OrderIntent intent, double px);
        double paperPosition(String strategyId, String symbol);
        double paperEntry(String strategyId, String symbol);
        Map<String, Double> paperSnapshot(String strategyId);
        /** 實盤送單前呼叫：影子分身以參考價成交、記錄參考價以計算滑價。 */
        void onLiveSend(String strategyId, OrderIntent intent, double refPx);
        /** 送單被同步拒絕 / 呼叫失敗。 */
        default void onLiveReject(String strategyId, OrderIntent intent, String reason) {}
        /** 依風險預算算下單量；≤ 0 = 不覆寫（用名目 / 固定量）。 */
        double sizeQty(String strategyId, String symbol, double px);
        default void onDecision(String strategyId, String symbol, OrderSide side, double px, String outcome) {}
        /** 機器人參數（風控 / 執行方式），沒有回預設。 */
        default double param(String strategyId, String key) { return 0; }
        /** 最佳買一 / 賣一 {bid, ask}；拿不到回 null（就不走限價優先）。 */
        default double[] quote(String symbol) { return null; }
    }
    private static final java.util.concurrent.ScheduledExecutorService MAKER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "maker-fallback"); t.setDaemon(true); return t;
    });
    private volatile Execution exec;
    public void setExecution(Execution e) { this.exec = e; }
    public String strategyId() { return strategyId; }
    /** SPOT（只做多）| USDT_M（U 本位合約，可做空） */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private volatile String market = "SPOT";
    public void setMarket(String m) { this.market = "USDT_M".equalsIgnoreCase(m) ? "USDT_M" : "SPOT"; }
    public String market() { return market; }
    public boolean futures() { return "USDT_M".equals(market); }
    /** 合約：每個標的第一次下單前設定槓桿（程序生命週期內一次） */
    private static final java.util.Set<String> LEVERAGE_SET = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public boolean isPaper() { Execution x = exec; return x != null && x.paper(strategyId); }

    /** 風控出場：市價賣出該標的全部部位（停損 / 移動停利 / 停利）。 */
    public void exit(String symbol, String reason) {
        double q = position(symbol);
        if (Math.abs(q) <= 1e-12 || isPending(symbol)) return;
        OrderSide side = q > 0 ? OrderSide.SELL : OrderSide.BUY;   // 多單賣出 / 空單回補
        log.warn("[{}] {} 觸發{}，市價{} {}", strategyId, symbol, reason, q > 0 ? "賣出" : "回補", Math.abs(q));
        lastSignal = side + " " + symbol + "（" + reason + "）"; lastSignalAt = System.currentTimeMillis(); signals.incrementAndGet();
        execute(new OrderIntent(java.util.UUID.randomUUID().toString(), strategyId, symbol,
                side, com.quant.common.enums.OrderType.MARKET, null, Math.abs(q), futures(), market, "main"), reason + "出場");
    }

    public StrategyContextImpl(String strategyId, OrderClient orderClient,
                               Map<String, Double> sharedPrices, DoubleSupplier orderNotional,
                               StrategyPositionBook book) {
        this.strategyId = strategyId;
        this.orderClient = orderClient;
        this.sharedPrices = sharedPrices;
        this.orderNotional = orderNotional;
        this.book = book;
    }

    public void setAllocation(double a) { this.allocation = Math.max(0, a); }
    public double getAllocation() { return allocation; }

    @Override
    public void submit(OrderIntent intent) {
        signals.incrementAndGet(); lastSignalAt = System.currentTimeMillis();
        lastSignal = intent.side() + " " + intent.symbol();
        log.info("[{}] 訊號 {} {}", strategyId, intent.side(), intent.symbol());
        if (!isPaper() && !book.isReconciled()) {
            log.warn("[{}] 部位尚未與 OMS 對帳完成，暫不下單 {}", strategyId, intent.symbol());
            decide(intent.symbol(), intent.side(), "未對帳，略過"); return;
        }
        if (isPending(intent.symbol())) {
            log.info("[{}] {} 仍有未結單，略過本次 {} 訊號", strategyId, intent.symbol(), intent.side());
            decide(intent.symbol(), intent.side(), "仍有未結單，略過"); return;
        }
        double pos = position(intent.symbol());
        if (!futures() && intent.side() == OrderSide.SELL && pos <= 1e-12) {
            decide(intent.symbol(), intent.side(), "現貨沒有部位可賣，略過"); return;
        }
        // 開倉（空手時的 BUY，或合約空手時的 SELL）先過路由：暫停 / 冷卻 / 盤勢 / 同時持倉上限 + 排名；平倉直接送
        BuyRouter r = buyRouter;
        if (Math.abs(pos) <= 1e-12 && r != null && r.route(this, intent)) return;
        execute(intent);
    }

    public void execute(OrderIntent intent) { execute(intent, null); }

    /** 實際送單（尺寸換算 → OMS / 模擬盤）。SELL 一律賣掉該標的全部部位，不按名目換算，避免賣不乾淨或超賣。 */
    void execute(OrderIntent intent, String why) {
        double notional = orderNotional.getAsDouble();
        double px = price(intent.symbol());
        double qty;
        double held = position(intent.symbol());
        Execution x = exec;
        boolean closing = (intent.side() == OrderSide.SELL && held > 1e-12) || (intent.side() == OrderSide.BUY && held < -1e-12);
        double sized = x == null || closing || px <= 0 ? 0 : x.sizeQty(strategyId, intent.symbol(), px);
        if (closing) {
            qty = Math.abs(held);                 // 平倉：多單全部賣掉 / 空單全部回補（反向訊號只平倉、不直接翻倉）
        } else if (sized > 0) {
            qty = sized * allocation;             // 風險預算下單量（波動率目標）
            if (qty * px < MIN_ORDER_USDT) qty = MIN_ORDER_USDT / px;
        } else if (notional > 0 && px > 0) {
            qty = (notional * allocation) / px;   // 名目下單：金額 / 現價
        } else {
            qty = intent.quantity() * allocation; // 沿用策略原始數量
        }
        if (qty <= 0) { decide(intent.symbol(), intent.side(), "數量為 0（現價 " + px + "）"); return; }
        OrderIntent scaled = new OrderIntent(intent.clientOrderId(), strategyId, intent.symbol(),
                intent.side(), intent.type(), intent.price(), qty, futures() && closing, market, intent.account());
        String tag = why == null ? "" : why + " · ";

        if (x != null && x.paper(strategyId)) {
            x.paperFill(strategyId, scaled, px);   // 模擬盤一律以現價成交（限價優先在模擬盤不假設會吃到買一價，保守）
            decide(scaled.symbol(), scaled.side(), tag + "模擬成交 " + fmt(qty) + " @" + fmt(px));
            return;
        }
        // 限價優先：只用在策略訊號（why == null）；停損 / 停利等風控出場一律市價，不賭成交
        if (why == null && x != null && x.param(strategyId, "execMaker") > 0) {
            double[] q = x.quote(scaled.symbol());
            double limit = q == null ? 0 : scaled.side() == OrderSide.BUY ? q[0] : q[1];
            if (limit > 0 && qty * limit >= MIN_ORDER_USDT * 0.95) {
                OrderIntent lim = new OrderIntent(scaled.clientOrderId(), strategyId, scaled.symbol(), scaled.side(),
                        com.quant.common.enums.OrderType.LIMIT, limit, qty, scaled.reduceOnly(), scaled.market(), scaled.account());
                if (px > 0) try { x.onLiveSend(strategyId, scaled, px); } catch (Exception ignore) {}
                final double intended = qty;
                if (send(lim, intended, "限價優先 @" + fmt(limit) + " · ")) {
                    long wait = Math.max(2, (long) x.param(strategyId, "makerWaitSec"));
                    MAKER.schedule(() -> makerFallback(lim, intended, wait), wait, java.util.concurrent.TimeUnit.SECONDS);
                }
                return;
            }
        }
        if (x != null && px > 0) try { x.onLiveSend(strategyId, scaled, px); } catch (Exception ignore) {}
        send(scaled, qty, tag);
    }

    /** 限價掛 N 秒還沒成交完：撤單，剩下的改市價補（賣出則把手上部位全部賣掉）。 */
    void makerFallback(OrderIntent lim, double intended, long waitSec) {
        String cid = lim.clientOrderId(), sym = lim.symbol();
        if (!book.isInflight(cid)) { book.forgetFilled(cid); return; }   // 已經成交完（或已被撤）
        boolean cancelled;
        try { var r = orderClient.cancel(cid, sym, lim.market() == null ? "SPOT" : lim.market()); cancelled = r != null && r.code() == 0; }
        catch (Exception e) { cancelled = false; log.warn("[{}] 撤限價單失敗 {}: {}", strategyId, cid, e.getMessage()); }
        if (!cancelled) { decide(sym, lim.side(), "限價 " + waitSec + " 秒未成交，撤單失敗（可能剛成交），等成交回報"); return; }
        // review #7：不再「等 1.5 秒再看本地成交量」——Kafka / UDS 慢一點就會低估成交、補買過量。
        // 改成向交易所查這張單，等到終態（CANCELED / FILLED / EXPIRED）拿交易所的累計成交量（executedQty）算剩多少。
        confirmAndRefill(lim, intended, waitSec, 0);
    }

    static final int CONFIRM_TRIES = 8;
    void confirmAndRefill(OrderIntent lim, double intended, long waitSec, int attempt) {
        String cid = lim.clientOrderId(), sym = lim.symbol();
        Double executed = null; String st = null;
        try {
            var r = orderClient.query(cid, sym, lim.market() == null ? "SPOT" : lim.market());
            if (r != null && r.code() == 0 && r.data() != null) {
                var n = JSON.readTree(r.data());
                st = n.path("status").asText("");
                if (n.path("dryRun").asBoolean(false)) executed = book.filled(cid);          // Dry-Run 沒有交易所數字：用本地
                else if (n.has("executedQty")) executed = n.path("executedQty").asDouble(0);
            }
        } catch (Exception e) { log.debug("[{}] 查撤單結果失敗 {}: {}", strategyId, cid, e.getMessage()); }
        boolean terminal = st != null && (st.equals("CANCELED") || st.equals("FILLED") || st.equals("EXPIRED") || st.equals("REJECTED"));
        if (!terminal || executed == null) {
            if (attempt + 1 >= CONFIRM_TRIES) {
                decide(sym, lim.side(), "撤單後 " + CONFIRM_TRIES + " 次都確認不到交易所的最終成交量，不補市價（避免超買）");
                return;
            }
            MAKER.schedule(() -> confirmAndRefill(lim, intended, waitSec, attempt + 1), 1, java.util.concurrent.TimeUnit.SECONDS);
            return;
        }
        book.forgetFilled(cid);
        double filled = executed;
        double px = price(sym);
        double held = position(sym);
        boolean closing = (lim.side() == OrderSide.SELL && held > 1e-12) || (lim.side() == OrderSide.BUY && held < -1e-12);
        // 開倉：剩多少 = 原本要買的 − 交易所確認成交（不受本地回報延遲影響，不會補買過量）
        // 平倉：以目前部位為準（本地回報若還沒到，部位看起來偏大；但合約 reduceOnly / 現貨餘額會擋住超賣）
        double rem = closing ? Math.abs(held) : intended - filled;
        if (rem <= 0 || px <= 0 || rem * px < MIN_ORDER_USDT) {
            decide(sym, lim.side(), String.format("限價成交 %s / %s（交易所確認），剩餘不足最低名目，不補市價", fmt(filled), fmt(intended)));
            return;
        }
        OrderIntent mkt = new OrderIntent(java.util.UUID.randomUUID().toString(), strategyId, sym, lim.side(),
                com.quant.common.enums.OrderType.MARKET, null, rem, lim.reduceOnly(), lim.market(), lim.account());
        send(mkt, rem, "限價 " + waitSec + " 秒成交 " + fmt(filled) + "（交易所確認）→ 剩餘改市價 · ");
    }

    /** 送 OMS；回 true = OMS 已接受。 */
    private boolean send(OrderIntent scaled, double qty, String tag) {
        Execution x = exec;
        double notional = orderNotional.getAsDouble();
        if (futures() && LEVERAGE_SET.add(scaled.symbol())) {
            int lev = x == null ? 1 : Math.max(1, (int) Math.round(x.param(strategyId, "leverage")));
            String err = null;
            try { var r = orderClient.leverage(scaled.symbol(), lev); if (r == null || r.code() != 0) err = r == null ? "null" : r.message(); }
            catch (Exception e) { err = e.getMessage(); }
            if (err != null) {
                LEVERAGE_SET.remove(scaled.symbol());
                log.warn("[{}] {} 設定合約槓桿 {}x 失敗（不下單）: {}", strategyId, scaled.symbol(), lev, err);
                decide(scaled.symbol(), scaled.side(), tag + "合約槓桿設定失敗，未下單：" + err); return false;
            }
            log.info("[{}] {} 合約槓桿設為 {}x", strategyId, scaled.symbol(), lev);
        }
        // review #7：送出「前」就登記在途。以前 HTTP 回來才登記，逾時（OMS 其實收到了）時 catch 又解除 → 下一根 K 線重複送單
        book.onSubmitted(scaled.clientOrderId(), strategyId, scaled.symbol());
        try {
            var resp = orderClient.submit(scaled);
            if (resp == null || resp.code() != 0) {
                // OMS 同步拒絕（風控 / filter / 餘額）或 202 對帳中：都不是成交
                if (resp != null && resp.code() == 202) {
                    // 狀態不明：OMS 會對帳並發終結事件；在那之前視為 pending（已登記）
                    log.warn("[{}] 下單狀態不明 {} {}：等待 OMS 對帳", strategyId, scaled.symbol(), scaled.clientOrderId());
                    decide(scaled.symbol(), scaled.side(), tag + "狀態不明，等待對帳");
                    return false;
                } else {
                    book.onSubmitFailed(scaled.clientOrderId());   // OMS 明確拒絕 = 確定沒送出
                    String m = resp == null ? "null" : resp.message();
                    log.warn("[{}] 下單被拒 {} {} qty={}: {}", strategyId, scaled.symbol(), scaled.side(), qty, m);
                    decide(scaled.symbol(), scaled.side(), tag + "被拒：" + m);
                    if (x != null) x.onLiveReject(strategyId, scaled, m);
                }
                return false;
            }
            decide(scaled.symbol(), scaled.side(), tag + "已送單 " + fmt(qty));
            log.info("[{}] 已送單 {} {} {} qty={} (名目{} 配置x{})，等待成交事件", strategyId, scaled.symbol(),
                    scaled.side(), scaled.type(), qty, notional > 0 ? notional : "-", allocation);
            return true;
        } catch (Exception e) {
            // 逾時 / 斷線：OMS 可能已收單 → 維持在途（UNKNOWN），30 秒後問 OMS 本地有沒有這張單再決定
            log.warn("[{}] 下單呼叫失敗（狀態不明，維持在途並回查）: {}", strategyId, e.getMessage());
            decide(scaled.symbol(), scaled.side(), tag + "OMS 回應失敗，狀態不明（不重送，回查中）：" + e.getMessage());
            MAKER.schedule(() -> verifyUnknown(scaled, x, 0), 30, java.util.concurrent.TimeUnit.SECONDS);
            return false;
        }
    }

    /** 送單狀態不明：OMS 有這張單 → 等它的終結事件；確定沒有 → 解除在途；查不到 OMS → 稍後再查（最多 10 次）。 */
    void verifyUnknown(OrderIntent scaled, Execution x, int attempt) {
        String cid = scaled.clientOrderId();
        if (!book.isInflight(cid)) return;   // 已收到終結事件
        try {
            var r = orderClient.local(cid);
            if (r != null && r.code() == 0 && r.data() != null) {
                if (Boolean.TRUE.equals(r.data().get("exists"))) { log.info("[{}] 狀態不明的 {} OMS 有收到（{}），等成交 / 終結事件", strategyId, cid, r.data().get("status")); return; }
                book.onSubmitFailed(cid);
                decide(scaled.symbol(), scaled.side(), "回查：OMS 沒有收到這張單，解除在途");
                if (x != null) x.onLiveReject(strategyId, scaled, "OMS 未收到");
                return;
            }
        } catch (Exception e) { log.debug("[{}] 回查 {} 失敗: {}", strategyId, cid, e.getMessage()); }
        if (attempt + 1 < 10) MAKER.schedule(() -> verifyUnknown(scaled, x, attempt + 1), 30, java.util.concurrent.TimeUnit.SECONDS);
        else decide(scaled.symbol(), scaled.side(), "回查 10 次 OMS 都不可達，維持在途；請檢查 order-execution-service");
    }

    private boolean isPending(String symbol) { return !isPaper() && book.isPending(strategyId, symbol); }
    public boolean pending(String symbol) { return isPending(symbol); }
    /** 任一標的有在途真實單。 */
    public boolean anyPending() { return book.hasPending(strategyId); }
    /** 真實（OMS）部位快照，不受目前階段影響——出清時要看真實的。 */
    public Map<String, Double> liveSnapshot() { return book.snapshot(strategyId); }

    @Override public double position(String symbol) {
        Execution x = exec;
        return x != null && x.paper(strategyId) ? x.paperPosition(strategyId, symbol) : book.position(strategyId, symbol);
    }
    /** 進場平均成本（模擬盤看影子引擎，實盤看 OMS 成交）。 */
    public double entry(String symbol) {
        Execution x = exec;
        return x != null && x.paper(strategyId) ? x.paperEntry(strategyId, symbol) : book.avgCost(strategyId, symbol);
    }
    public Map<String, Double> snapshot() {
        Execution x = exec;
        return x != null && x.paper(strategyId) ? x.paperSnapshot(strategyId) : book.snapshot(strategyId);
    }
    /** 有部位或未結單的標的數。 */
    public int openSlots() {
        if (!isPaper()) return book.openSlots(strategyId);
        int n = 0; for (double q : snapshot().values()) if (Math.abs(q) > 1e-12) n++;
        return n;
    }
    @Override public double price(String symbol) { return sharedPrices.getOrDefault(symbol, 0.0); }
    public double totalPosition() { return snapshot().values().stream().mapToDouble(Double::doubleValue).sum(); }

    private static String fmt(double v) {
        return java.math.BigDecimal.valueOf(v).round(new java.math.MathContext(6)).stripTrailingZeros().toPlainString();
    }
}
