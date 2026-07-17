package com.quant.common.strategy;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;
import java.util.UUID;

/** EMA 交叉機器人：快 EMA 上穿慢 EMA 買、下穿賣。 */
public class EmaStrategy implements Strategy {
    private final String id; private final double kf, ks, qty;
    private Double emaF, emaS, prevDiff;
    public EmaStrategy(String id, int fast, int slow, double qty) {
        this.id = id; this.kf = 2.0/(fast+1); this.ks = 2.0/(slow+1); this.qty = qty;
    }
    @Override public String id() { return id; }
    @Override public void onBar(Kline b, StrategyContext ctx) {
        if (!b.closed()) return;
        double c = b.close();
        emaF = emaF == null ? c : c*kf + emaF*(1-kf);
        emaS = emaS == null ? c : c*ks + emaS*(1-ks);
        double diff = emaF - emaS;
        if (prevDiff != null) {
            if (prevDiff <= 0 && diff > 0 && ctx.position(b.symbol()) <= 0) ctx.submit(o(b.symbol(), OrderSide.BUY));
            else if (prevDiff >= 0 && diff < 0 && ctx.position(b.symbol()) > 0) ctx.submit(o(b.symbol(), OrderSide.SELL));
        }
        prevDiff = diff;
    }
    private OrderIntent o(String s, OrderSide side){ return new OrderIntent(UUID.randomUUID().toString(), id, s, side, OrderType.MARKET, null, qty, false, "SPOT", "main"); }
}
