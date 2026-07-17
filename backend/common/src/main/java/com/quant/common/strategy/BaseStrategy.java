package com.quant.common.strategy;

import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;
import java.util.UUID;

/** 策略基底：提供統一下單輔助，子類只需實作 onBar。 */
public abstract class BaseStrategy implements Strategy {
    protected final String id;
    protected final double qty;
    protected BaseStrategy(String id, double qty) { this.id = id; this.qty = qty; }
    @Override public String id() { return id; }
    protected void buy(StrategyContext ctx, String s) { if (ctx.position(s) <= 0) ctx.submit(order(s, OrderSide.BUY)); }
    protected void sell(StrategyContext ctx, String s) { if (ctx.position(s) > 0) ctx.submit(order(s, OrderSide.SELL)); }
    private OrderIntent order(String s, OrderSide side) {
        return new OrderIntent(UUID.randomUUID().toString(), id, s, side, OrderType.MARKET, null, qty, false, "SPOT", "main");
    }
}
