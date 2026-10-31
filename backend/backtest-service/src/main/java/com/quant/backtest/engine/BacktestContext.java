package com.quant.backtest.engine;

import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.strategy.StrategyContext;

import java.util.HashMap;
import java.util.Map;

/**
 * 回測撮合上下文（見架構文件 §7.1、§7.2）：
 * 以「資金比例」下注 —— 買進訊號投入 posFraction 比例的現金、賣出訊號清倉，
 * 使權益曲線反映策略真實報酬（避免固定微量部位導致報酬趨近 0）。含滑點與手續費。
 */
public class BacktestContext implements StrategyContext {

    private final double fee, slippage, posFraction;
    private double cash;
    private int trades = 0;
    private double filledNotional = 0, filledQty = 0;
    private final Map<String, Double> positions = new HashMap<>();
    private final Map<String, Double> prices = new HashMap<>();
    private final java.util.List<Trade> tradeLog = new java.util.ArrayList<>();
    private long barTime = 0;

    /** 逐筆成交紀錄（一致性比對用）。 */
    public record Trade(long time, String symbol, OrderSide side, double qty, double price) {}

    public BacktestContext(double initialCash, double fee, double slippage) {
        this(initialCash, fee, slippage, 0.95);
    }
    public BacktestContext(double initialCash, double fee, double slippage, double posFraction) {
        this.cash = initialCash; this.fee = fee; this.slippage = slippage; this.posFraction = posFraction;
    }

    @Override
    public void submit(OrderIntent intent) {
        String sym = intent.symbol();
        double px = prices.getOrDefault(sym, 0.0);
        if (px <= 0) return;
        boolean buy = intent.side() == OrderSide.BUY;
        double fillPx = px * (1 + (buy ? slippage : -slippage));
        double pos = positions.getOrDefault(sym, 0.0);

        double qty;
        if (buy) {
            if (pos > 0) return;                       // 已持有,不重複進場
            double notional = cash * posFraction;      // 以比例現金買進
            qty = notional / fillPx;
            if (qty <= 0) return;
            cash -= notional + notional * fee;
            positions.merge(sym, qty, Double::sum);
        } else {
            if (pos <= 0) return;                       // 無部位可賣
            qty = pos;                                  // 清倉
            double notional = qty * fillPx;
            cash += notional - notional * fee;
            positions.put(sym, 0.0);
        }
        trades++; filledQty += qty; filledNotional += qty * fillPx;
        tradeLog.add(new Trade(barTime, sym, intent.side(), qty, fillPx));
    }

    public void setBarTime(long t) { this.barTime = t; }
    public java.util.List<Trade> tradeLog() { return tradeLog; }

    @Override public double position(String symbol) { return positions.getOrDefault(symbol, 0.0); }
    @Override public double price(String symbol) { return prices.getOrDefault(symbol, 0.0); }
    public void updatePrice(String symbol, double p) { prices.put(symbol, p); }
    public int trades() { return trades; }
    public double avgFillPrice() { return filledQty > 0 ? filledNotional / filledQty : 0; }

    public double equity() {
        double eq = cash;
        for (var e : positions.entrySet()) eq += e.getValue() * prices.getOrDefault(e.getKey(), 0.0);
        return eq;
    }
}
