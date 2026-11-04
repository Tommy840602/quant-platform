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

    /** 逐筆成交紀錄（一致性比對用）。action：開多 / 平多 / 開空 / 平空。 */
    public record Trade(long time, String symbol, OrderSide side, double qty, double price, String action) {
        public Trade(long time, String symbol, OrderSide side, double qty, double price) { this(time, symbol, side, qty, price, null); }
    }

    public BacktestContext(double initialCash, double fee, double slippage) {
        this(initialCash, fee, slippage, 0.95);
    }
    public BacktestContext(double initialCash, double fee, double slippage, double posFraction) {
        this.cash = initialCash; this.fee = fee; this.slippage = slippage; this.posFraction = posFraction;
    }

    private final Map<String, Double> entryPx = new HashMap<>();

    @Override
    public void submit(OrderIntent intent) { submitAt(intent, prices.getOrDefault(intent.symbol(), 0.0), 0); }

    /**
     * 以指定價格成交（停損 / 停利在 K 線內觸價時用觸發價，而不是收盤價）。
     * qtyOverride &gt; 0：買進數量由外部決定（每單名目比例 / 波動率目標），受可用現金限制；0 = 用 posFraction。
     * @return 成交紀錄；沒成交回 null
     */
    public Trade submitAt(OrderIntent intent, double px, double qtyOverride) {
        String sym = intent.symbol();
        if (px <= 0) return null;
        boolean buy = intent.side() == OrderSide.BUY;
        double pos = positions.getOrDefault(sym, 0.0);
        if (buy && pos < -1e-12) return cover(sym, px);   // 手上是空單：BUY = 回補
        double fillPx = px * (1 + (buy ? slippage : -slippage));

        double qty;
        if (buy) {
            if (pos > 0) return null;                  // 已持有,不重複進場
            double notional = qtyOverride > 0 ? Math.min(qtyOverride * fillPx, cash / (1 + fee)) : cash * posFraction;
            qty = notional / fillPx;
            if (qty <= 0 || notional <= 0) return null;
            cash -= notional + notional * fee;
            positions.merge(sym, qty, Double::sum);
            entryPx.put(sym, fillPx);
        } else {
            if (pos <= 0) return null;                  // 無部位可賣（現貨不會因 SELL 變空單；開空走 openShort）
            qty = pos;                                  // 清倉
            double notional = qty * fillPx;
            cash += notional - notional * fee;
            positions.put(sym, 0.0);
            entryPx.remove(sym);
        }
        return record(sym, intent.side(), qty, fillPx, buy ? "開多" : "平多");
    }

    /**
     * 開空（U 本位合約、1 倍）：空單名目不超過權益；賣出所得計入現金、部位記負數，
     * 權益 = 現金 + 部位 × 價（負部位 → 價格漲權益減），與實盤合約損益一致。
     */
    public Trade openShort(String sym, double px, double qtyOverride) {
        if (px <= 0 || Math.abs(positions.getOrDefault(sym, 0.0)) > 1e-12) return null;
        double fillPx = px * (1 - slippage);
        double cap = equity() / (1 + fee);
        double notional = qtyOverride > 0 ? Math.min(qtyOverride * fillPx, cap) : cap * posFraction;
        if (notional <= 0) return null;
        double qty = notional / fillPx;
        cash += notional - notional * fee;
        positions.put(sym, -qty);
        entryPx.put(sym, fillPx);
        return record(sym, OrderSide.SELL, qty, fillPx, "開空");
    }

    /** 回補整筆空單。 */
    public Trade cover(String sym, double px) {
        double pos = positions.getOrDefault(sym, 0.0);
        if (px <= 0 || pos >= -1e-12) return null;
        double fillPx = px * (1 + slippage), qty = -pos, notional = qty * fillPx;
        cash -= notional + notional * fee;
        positions.put(sym, 0.0);
        entryPx.remove(sym);
        return record(sym, OrderSide.BUY, qty, fillPx, "平空");
    }

    private Trade record(String sym, OrderSide side, double qty, double fillPx, String action) {
        trades++; filledQty += qty; filledNotional += qty * fillPx;
        Trade t = new Trade(barTime, sym, side, qty, fillPx, action);
        tradeLog.add(t);
        return t;
    }

    /** 目前持倉的進場成交價（含滑價）；空手回 0。 */
    public double entry(String symbol) { return entryPx.getOrDefault(symbol, 0.0); }
    public double fee() { return fee; }

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
