package com.quant.common.strategy;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** KDJ 機器人：J 低於 20(超賣)買、高於 80(超買)賣。 */
public class KdjStrategy implements Strategy {
    private final String id; private final int period; private final double qty;
    private final Deque<double[]> hlc = new ArrayDeque<>();  // [high, low, close]
    private double k = 50, d = 50;
    public KdjStrategy(String id, int period, double qty) { this.id = id; this.period = period; this.qty = qty; }
    @Override public String id() { return id; }
    @Override public void onBar(Kline b, StrategyContext ctx) {
        if (!b.closed()) return;
        hlc.addLast(new double[]{b.high(), b.low(), b.close()});
        while (hlc.size() > period) hlc.removeFirst();
        if (hlc.size() < period) return;
        double hh = -1e18, ll = 1e18;
        for (double[] x : hlc) { hh = Math.max(hh, x[0]); ll = Math.min(ll, x[1]); }
        double rsv = hh == ll ? 50 : (b.close() - ll) / (hh - ll) * 100;
        k = 2.0/3*k + 1.0/3*rsv; d = 2.0/3*d + 1.0/3*k;
        double j = 3*k - 2*d;
        if (j < 20 && ctx.position(b.symbol()) <= 0) ctx.submit(o(b.symbol(), OrderSide.BUY));
        else if (j > 80 && ctx.position(b.symbol()) > 0) ctx.submit(o(b.symbol(), OrderSide.SELL));
    }
    private OrderIntent o(String s, OrderSide side){ return new OrderIntent(UUID.randomUUID().toString(), id, s, side, OrderType.MARKET, null, qty, false, "SPOT", "main"); }
}
