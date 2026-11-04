package com.quant.backtest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 回測交易成本：手續費預設 0.1%（現貨 taker，與實盤 account.fee-rate 一致；以前寫死 0.04% 是合約費率），
 * 滑價優先用「這個幣在實盤量到的平均滑價」（近 30 天、至少 5 筆），沒有才用預設 2 bps。
 */
@Service
public class CostModel {

    private static final Logger log = LoggerFactory.getLogger(CostModel.class);
    public record Costs(double fee, double slippage, String slippageSource, int samples) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("feeRate", fee); m.put("slippageBps", Math.round(slippage * 1e4 * 100) / 100.0);
            m.put("slippageSource", slippageSource); m.put("samples", samples);
            return m;
        }
    }

    static final int MIN_SAMPLES = 5;
    @Value("${backtest.fee-rate:0.001}") private double defaultFee;
    @Value("${backtest.slippage:0.0002}") private double defaultSlippage;

    private final StrategyClient strategy;
    private volatile Map<String, Map<String, Object>> cache = Map.of();
    private volatile long cachedAt = 0;

    public CostModel(StrategyClient strategy) { this.strategy = strategy; }

    /** fee / slippage 有指定（&gt; 0）就照用；model = "fixed" 不查實測。 */
    public Costs resolve(String symbol, double fee, double slippage, String model) {
        double f = fee > 0 ? fee : defaultFee;
        if (slippage > 0) return new Costs(f, slippage, "指定", 0);
        if (!"fixed".equalsIgnoreCase(model)) {
            Map<String, Object> m = measured().get(symbol == null ? "" : symbol.toUpperCase());
            if (m != null && m.get("n") instanceof Number n && n.intValue() >= MIN_SAMPLES && m.get("bps") instanceof Number b)
                return new Costs(f, Math.max(0, b.doubleValue()) / 1e4, "實測", n.intValue());
        }
        return new Costs(f, defaultSlippage, "預設", 0);
    }

    private Map<String, Map<String, Object>> measured() {
        if (System.currentTimeMillis() - cachedAt < 300_000) return cache;
        try {
            var r = strategy.slippage(30);
            if (r != null && r.code() == 0 && r.data() != null) cache = r.data();
        } catch (Exception e) { log.debug("讀取實測滑價失敗（用預設）: {}", e.getMessage()); }
        cachedAt = System.currentTimeMillis();
        return cache;
    }
}
