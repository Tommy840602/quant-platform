package com.quant.strategy.core;

import com.quant.common.dto.OrderIntent;
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
 */
public class StrategyContextImpl implements StrategyContext {

    private static final Logger log = LoggerFactory.getLogger(StrategyContextImpl.class);
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
    private void outcome(String o) { lastOutcome = o; }

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
        if (!book.isReconciled()) {
            log.warn("[{}] 部位尚未與 OMS 對帳完成，暫不下單 {}", strategyId, intent.symbol());
            outcome("未對帳，略過"); return;
        }
        if (book.isPending(strategyId, intent.symbol())) {
            log.info("[{}] {} 仍有未結單，略過本次 {} 訊號", strategyId, intent.symbol(), intent.side());
            outcome("仍有未結單，略過"); return;
        }
        double notional = orderNotional.getAsDouble();
        double px = price(intent.symbol());
        double qty;
        if (notional > 0 && px > 0) {
            qty = (notional * allocation) / px;   // 名目下單：金額 / 現價
        } else {
            qty = intent.quantity() * allocation; // 沿用策略原始數量
        }
        if (qty <= 0) { outcome("數量為 0（現價 " + px + "）"); return; }
        OrderIntent scaled = new OrderIntent(intent.clientOrderId(), strategyId, intent.symbol(),
                intent.side(), intent.type(), intent.price(), qty, intent.reduceOnly(), intent.market(), intent.account());
        try {
            var resp = orderClient.submit(scaled);
            if (resp == null || resp.code() != 0) {
                // OMS 同步拒絕（風控 / filter / 餘額）或 202 對帳中：都不是成交
                book.onSubmitFailed(scaled.clientOrderId());
                if (resp != null && resp.code() == 202) {
                    // 狀態不明：OMS 會對帳並發終結事件；在那之前視為 pending
                    book.onSubmitted(scaled.clientOrderId(), strategyId, scaled.symbol());
                    log.warn("[{}] 下單狀態不明 {} {}：等待 OMS 對帳", strategyId, scaled.symbol(), scaled.clientOrderId());
                    outcome("狀態不明，等待對帳");
                } else {
                    log.warn("[{}] 下單被拒 {} {} qty={}: {}", strategyId, scaled.symbol(), scaled.side(), qty,
                            resp == null ? "null" : resp.message());
                    outcome("被拒：" + (resp == null ? "null" : resp.message()));
                }
                return;
            }
            book.onSubmitted(scaled.clientOrderId(), strategyId, scaled.symbol());
            outcome("已送單");
            log.info("[{}] 已送單 {} {} qty={} (名目{} 配置x{})，等待成交事件", strategyId, scaled.symbol(),
                    scaled.side(), qty, notional > 0 ? notional : "-", allocation);
        } catch (Exception e) {
            book.onSubmitFailed(scaled.clientOrderId());
            log.warn("[{}] 下單呼叫失敗（OMS 不可達）: {}", strategyId, e.getMessage());
            outcome("OMS 不可達：" + e.getMessage());
        }
    }

    @Override public double position(String symbol) { return book.position(strategyId, symbol); }
    @Override public double price(String symbol) { return sharedPrices.getOrDefault(symbol, 0.0); }
    public double totalPosition() { return book.totalPosition(strategyId); }
}
