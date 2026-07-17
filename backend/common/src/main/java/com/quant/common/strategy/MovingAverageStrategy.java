package com.quant.common.strategy;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** 範例策略：雙均線交叉（僅示意）。id 可設定以支援多策略並行。 */
public class MovingAverageStrategy implements Strategy {

    private final String id;
    private final int fast;
    private final int slow;
    private final double qty;
    private final Deque<Double> closes = new ArrayDeque<>();
    private Double prevFast, prevSlow;

    public MovingAverageStrategy() { this("ma-cross", 3, 8, 0.001); }
    public MovingAverageStrategy(int fast, int slow, double qty) { this("ma-" + fast + "-" + slow, fast, slow, qty); }
    public MovingAverageStrategy(String id, int fast, int slow, double qty) {
        this.id = id; this.fast = fast; this.slow = slow; this.qty = qty;
    }

    @Override public String id() { return id; }

    @Override
    public void onBar(Kline bar, StrategyContext ctx) {
        if (!bar.closed()) return;
        closes.addLast(bar.close());
        while (closes.size() > slow) closes.removeFirst();
        if (closes.size() < slow) return;

        double f = sma(fast), s = sma(slow);
        if (prevFast != null && prevSlow != null) {
            boolean golden = prevFast <= prevSlow && f > s;
            boolean death  = prevFast >= prevSlow && f < s;
            if (golden && ctx.position(bar.symbol()) <= 0) ctx.submit(order(bar.symbol(), OrderSide.BUY));
            else if (death && ctx.position(bar.symbol()) > 0) ctx.submit(order(bar.symbol(), OrderSide.SELL));
        }
        prevFast = f; prevSlow = s;
    }

    private double sma(int n) {
        return closes.stream().skip(Math.max(0, closes.size() - n))
                .mapToDouble(Double::doubleValue).average().orElse(0);
    }
    private OrderIntent order(String symbol, OrderSide side) {
        return new OrderIntent(UUID.randomUUID().toString(), id, symbol,
                side, OrderType.MARKET, null, qty, false, "SPOT", "main");
    }
}
