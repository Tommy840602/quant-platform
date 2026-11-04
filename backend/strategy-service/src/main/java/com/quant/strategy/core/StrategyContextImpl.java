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
    }
    private volatile Execution exec;
    public void setExecution(Execution e) { this.exec = e; }
    public String strategyId() { return strategyId; }
    public boolean isPaper() { Execution x = exec; return x != null && x.paper(strategyId); }

    /** 風控出場：市價賣出該標的全部部位（停損 / 移動停利 / 停利）。 */
    public void exit(String symbol, String reason) {
        double q = position(symbol);
        if (q <= 1e-12 || isPending(symbol)) return;
        log.warn("[{}] {} 觸發{}，市價賣出 {}", strategyId, symbol, reason, q);
        lastSignal = "SELL " + symbol + "（" + reason + "）"; lastSignalAt = System.currentTimeMillis(); signals.incrementAndGet();
        execute(new OrderIntent(java.util.UUID.randomUUID().toString(), strategyId, symbol,
                OrderSide.SELL, com.quant.common.enums.OrderType.MARKET, null, q, false, "SPOT", "main"), reason + "出場");
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
        BuyRouter r = buyRouter;
        if (intent.side() == OrderSide.BUY && r != null && r.route(this, intent)) return;
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
        double sized = x == null || intent.side() == OrderSide.SELL || px <= 0 ? 0 : x.sizeQty(strategyId, intent.symbol(), px);
        if (intent.side() == OrderSide.SELL && held > 1e-12) {
            qty = held;                           // 出場：全部賣掉
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
                intent.side(), intent.type(), intent.price(), qty, intent.reduceOnly(), intent.market(), intent.account());
        String tag = why == null ? "" : why + " · ";

        if (x != null && x.paper(strategyId)) {
            x.paperFill(strategyId, scaled, px);
            decide(scaled.symbol(), scaled.side(), tag + "模擬成交 " + fmt(qty) + " @" + fmt(px));
            return;
        }
        if (x != null && px > 0) try { x.onLiveSend(strategyId, scaled, px); } catch (Exception ignore) {}
        try {
            var resp = orderClient.submit(scaled);
            if (resp == null || resp.code() != 0) {
                // OMS 同步拒絕（風控 / filter / 餘額）或 202 對帳中：都不是成交
                book.onSubmitFailed(scaled.clientOrderId());
                if (resp != null && resp.code() == 202) {
                    // 狀態不明：OMS 會對帳並發終結事件；在那之前視為 pending
                    book.onSubmitted(scaled.clientOrderId(), strategyId, scaled.symbol());
                    log.warn("[{}] 下單狀態不明 {} {}：等待 OMS 對帳", strategyId, scaled.symbol(), scaled.clientOrderId());
                    decide(scaled.symbol(), scaled.side(), tag + "狀態不明，等待對帳");
                } else {
                    String m = resp == null ? "null" : resp.message();
                    log.warn("[{}] 下單被拒 {} {} qty={}: {}", strategyId, scaled.symbol(), scaled.side(), qty, m);
                    decide(scaled.symbol(), scaled.side(), tag + "被拒：" + m);
                    if (x != null) x.onLiveReject(strategyId, scaled, m);
                }
                return;
            }
            book.onSubmitted(scaled.clientOrderId(), strategyId, scaled.symbol());
            decide(scaled.symbol(), scaled.side(), tag + "已送單 " + fmt(qty));
            log.info("[{}] 已送單 {} {} qty={} (名目{} 配置x{})，等待成交事件", strategyId, scaled.symbol(),
                    scaled.side(), qty, notional > 0 ? notional : "-", allocation);
        } catch (Exception e) {
            book.onSubmitFailed(scaled.clientOrderId());
            log.warn("[{}] 下單呼叫失敗（OMS 不可達）: {}", strategyId, e.getMessage());
            decide(scaled.symbol(), scaled.side(), tag + "OMS 不可達：" + e.getMessage());
            if (x != null) x.onLiveReject(strategyId, scaled, "OMS 不可達");
        }
    }

    private boolean isPending(String symbol) { return !isPaper() && book.isPending(strategyId, symbol); }
    public boolean pending(String symbol) { return isPending(symbol); }

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
        int n = 0; for (double q : snapshot().values()) if (q > 1e-12) n++;
        return n;
    }
    @Override public double price(String symbol) { return sharedPrices.getOrDefault(symbol, 0.0); }
    public double totalPosition() { return snapshot().values().stream().mapToDouble(Double::doubleValue).sum(); }

    private static String fmt(double v) {
        return java.math.BigDecimal.valueOf(v).round(new java.math.MathContext(6)).stripTrailingZeros().toPlainString();
    }
}
