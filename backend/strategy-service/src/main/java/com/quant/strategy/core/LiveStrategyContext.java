package com.quant.strategy.core;

import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.strategy.StrategyContext;
import com.quant.strategy.service.OrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 實盤策略上下文：submit 透過 OrderClient 送往 OMS（見架構文件 §7.4）。
 * 部位為樂觀估計；正式應以 order.events 成交回報為準。
 */
@Service
public class LiveStrategyContext implements StrategyContext {

    private static final Logger log = LoggerFactory.getLogger(LiveStrategyContext.class);
    private final OrderClient orderClient;
    private final Map<String, Double> positions = new ConcurrentHashMap<>();
    private final Map<String, Double> prices = new ConcurrentHashMap<>();

    public LiveStrategyContext(OrderClient orderClient) { this.orderClient = orderClient; }

    @Override
    public void submit(OrderIntent intent) {
        try {
            var resp = orderClient.submit(intent);
            double signed = intent.side() == OrderSide.BUY ? intent.quantity() : -intent.quantity();
            positions.merge(intent.symbol(), signed, Double::sum);
            log.info("策略下單 {} {} {} qty={} -> {}", intent.symbol(), intent.side(),
                    intent.type(), intent.quantity(), resp.message());
        } catch (Exception e) {
            log.warn("下單失敗: {}", e.getMessage());
        }
    }

    @Override public double position(String symbol) { return positions.getOrDefault(symbol, 0.0); }
    @Override public double price(String symbol) { return prices.getOrDefault(symbol, 0.0); }
    public void updatePrice(String symbol, double p) { prices.put(symbol, p); }
}
