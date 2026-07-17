package com.quant.common.strategy;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** RSI 均值回歸機器人：超賣買進、超買賣出（僅示意）。 */
public class RsiStrategy implements Strategy {

    private final String id;
    private final int window;
    private final double oversold, overbought, qty;
    private final Deque<Double> closes = new ArrayDeque<>();

    public RsiStrategy(String id, int window, double oversold, double overbought, double qty) {
        this.id = id; this.window = window; this.oversold = oversold; this.overbought = overbought; this.qty = qty;
    }

    @Override public String id() { return id; }

    @Override
    public void onBar(Kline bar, StrategyContext ctx) {
        if (!bar.closed()) return;
        closes.addLast(bar.close());
        while (closes.size() > window + 1) closes.removeFirst();
        if (closes.size() < window + 1) return;
        double rsi = rsi();
        if (rsi < oversold && ctx.position(bar.symbol()) <= 0) ctx.submit(order(bar.symbol(), OrderSide.BUY));
        else if (rsi > overbought && ctx.position(bar.symbol()) > 0) ctx.submit(order(bar.symbol(), OrderSide.SELL));
    }

    private double rsi() {
        Double prev = null; double gain = 0, loss = 0; int n = 0;
        for (double c : closes) {
            if (prev != null) { double d = c - prev; if (d >= 0) gain += d; else loss -= d; n++; }
            prev = c;
        }
        double avgG = gain / n, avgL = loss / n;
        double rs = avgL == 0 ? 100 : avgG / avgL;
        return 100 - 100 / (1 + rs);
    }
    private OrderIntent order(String symbol, OrderSide side) {
        return new OrderIntent(UUID.randomUUID().toString(), id, symbol, side, OrderType.MARKET, null, qty, false, "SPOT", "main");
    }
}
