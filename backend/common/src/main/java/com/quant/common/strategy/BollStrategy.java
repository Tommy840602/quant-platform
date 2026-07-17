package com.quant.common.strategy;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** 布林通道均值回歸機器人：觸下軌買、觸上軌賣。 */
public class BollStrategy implements Strategy {
    private final String id; private final int period; private final double mult, qty;
    private final Deque<Double> closes = new ArrayDeque<>();
    public BollStrategy(String id, int period, double mult, double qty) {
        this.id = id; this.period = period; this.mult = mult; this.qty = qty;
    }
    @Override public String id() { return id; }
    @Override public void onBar(Kline b, StrategyContext ctx) {
        if (!b.closed()) return;
        closes.addLast(b.close());
        while (closes.size() > period) closes.removeFirst();
        if (closes.size() < period) return;
        double mean = closes.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = closes.stream().mapToDouble(x -> (x-mean)*(x-mean)).average().orElse(0);
        double sd = Math.sqrt(var), c = b.close();
        if (c <= mean - mult*sd && ctx.position(b.symbol()) <= 0) ctx.submit(o(b.symbol(), OrderSide.BUY));
        else if (c >= mean + mult*sd && ctx.position(b.symbol()) > 0) ctx.submit(o(b.symbol(), OrderSide.SELL));
    }
    private OrderIntent o(String s, OrderSide side){ return new OrderIntent(UUID.randomUUID().toString(), id, s, side, OrderType.MARKET, null, qty, false, "SPOT", "main"); }
}
