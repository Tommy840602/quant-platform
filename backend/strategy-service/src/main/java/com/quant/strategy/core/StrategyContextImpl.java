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
    private final java.util.function.Supplier<String> accountSupplier;   // 目前啟用的帳戶（paper | main）
    private volatile double allocation = 1.0;   // 資金配置倍率

    public StrategyContextImpl(String strategyId, OrderClient orderClient,
                               Map<String, Double> sharedPrices, DoubleSupplier orderNotional,
                               StrategyPositionBook book) {
        this(strategyId, orderClient, sharedPrices, orderNotional, book, () -> com.quant.common.account.Accounts.DEFAULT_MODE);
    }

    public StrategyContextImpl(String strategyId, OrderClient orderClient,
                               Map<String, Double> sharedPrices, DoubleSupplier orderNotional,
                               StrategyPositionBook book, java.util.function.Supplier<String> accountSupplier) {
        this.strategyId = strategyId;
        this.orderClient = orderClient;
        this.sharedPrices = sharedPrices;
        this.orderNotional = orderNotional;
        this.book = book;
        this.accountSupplier = accountSupplier;
    }

    public void setAllocation(double a) { this.allocation = Math.max(0, a); }
    public double getAllocation() { return allocation; }

    @Override
    public void submit(OrderIntent intent) {
        if (!book.isReconciled()) {
            log.warn("[{}] 部位尚未與 OMS 對帳完成，暫不下單 {}", strategyId, intent.symbol());
            return;
        }
        if (book.isPending(strategyId, intent.symbol())) {
            log.info("[{}] {} 仍有未結單，略過本次 {} 訊號", strategyId, intent.symbol(), intent.side());
            return;
        }
        double notional = orderNotional.getAsDouble();
        double px = price(intent.symbol());
        double qty;
        if (notional > 0 && px > 0) {
            qty = (notional * allocation) / px;   // 名目下單：金額 / 現價
        } else {
            qty = intent.quantity() * allocation; // 沿用策略原始數量
        }
        if (qty <= 0) return;
        // 策略類別裡寫死的 "main" 不算數：機器人跟著平台目前切換的帳戶（虛擬 / 真實）下單
        OrderIntent scaled = new OrderIntent(intent.clientOrderId(), strategyId, intent.symbol(),
                intent.side(), intent.type(), intent.price(), qty, intent.reduceOnly(), intent.market(), accountSupplier.get());
        try {
            var resp = orderClient.submit(scaled);
            if (resp == null || resp.code() != 0) {
                // OMS 同步拒絕（風控 / filter / 餘額）或 202 對帳中：都不是成交
                book.onSubmitFailed(scaled.clientOrderId());
                if (resp != null && resp.code() == 202) {
                    // 狀態不明：OMS 會對帳並發終結事件；在那之前視為 pending
                    book.onSubmitted(scaled.clientOrderId(), strategyId, scaled.symbol());
                    log.warn("[{}] 下單狀態不明 {} {}：等待 OMS 對帳", strategyId, scaled.symbol(), scaled.clientOrderId());
                } else {
                    log.warn("[{}] 下單被拒 {} {} qty={}: {}", strategyId, scaled.symbol(), scaled.side(), qty,
                            resp == null ? "null" : resp.message());
                }
                return;
            }
            book.onSubmitted(scaled.clientOrderId(), strategyId, scaled.symbol());
            log.info("[{}] 已送單 {} {} qty={} (名目{} 配置x{})，等待成交事件", strategyId, scaled.symbol(),
                    scaled.side(), qty, notional > 0 ? notional : "-", allocation);
        } catch (Exception e) {
            book.onSubmitFailed(scaled.clientOrderId());
            log.warn("[{}] 下單呼叫失敗（OMS 不可達）: {}", strategyId, e.getMessage());
        }
    }

    @Override public double position(String symbol) { return book.position(strategyId, symbol); }
    @Override public double price(String symbol) { return sharedPrices.getOrDefault(symbol, 0.0); }
    public double totalPosition() { return book.totalPosition(strategyId); }
}
