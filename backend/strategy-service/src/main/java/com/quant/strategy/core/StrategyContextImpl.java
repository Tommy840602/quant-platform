package com.quant.strategy.core;

import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.strategy.StrategyContext;
import com.quant.strategy.service.OrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 單策略上下文（多策略並行各持一份）。下單量依「資金配置倍率」縮放，實現實盤資金配置。
 */
public class StrategyContextImpl implements StrategyContext {

    private static final Logger log = LoggerFactory.getLogger(StrategyContextImpl.class);
    private final String strategyId;
    private final OrderClient orderClient;
    private final Map<String, Double> sharedPrices;
    private final Map<String, Double> positions = new ConcurrentHashMap<>();
    private volatile double allocation = 1.0;   // 資金配置倍率

    public StrategyContextImpl(String strategyId, OrderClient orderClient, Map<String, Double> sharedPrices) {
        this.strategyId = strategyId;
        this.orderClient = orderClient;
        this.sharedPrices = sharedPrices;
    }

    public void setAllocation(double a) { this.allocation = Math.max(0, a); }
    public double getAllocation() { return allocation; }

    @Override
    public void submit(OrderIntent intent) {
        double qty = intent.quantity() * allocation;
        if (qty <= 0) return;
        OrderIntent scaled = new OrderIntent(intent.clientOrderId(), intent.strategyId(), intent.symbol(),
                intent.side(), intent.type(), intent.price(), qty, intent.reduceOnly(), intent.market(), intent.account());
        try {
            var resp = orderClient.submit(scaled);
            double signed = scaled.side() == OrderSide.BUY ? qty : -qty;
            positions.merge(scaled.symbol(), signed, Double::sum);
            log.info("[{}] 下單 {} {} qty={} (配置x{}) -> {}", strategyId, scaled.symbol(),
                    scaled.side(), qty, allocation, resp.message());
        } catch (Exception e) { log.warn("[{}] 下單失敗: {}", strategyId, e.getMessage()); }
    }

    @Override public double position(String symbol) { return positions.getOrDefault(symbol, 0.0); }
    @Override public double price(String symbol) { return sharedPrices.getOrDefault(symbol, 0.0); }
    public double totalPosition() { return positions.values().stream().mapToDouble(Double::doubleValue).sum(); }
}
