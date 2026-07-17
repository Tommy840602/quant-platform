package com.quant.common.strategy;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;
import java.util.UUID;

/** MACD 機器人：DIF 上穿 DEA(柱狀由負轉正)買、下穿賣。 */
public class MacdStrategy implements Strategy {
    private final String id; private final double kf, ks, ksig, qty;
    private Double emaF, emaS, dea, prevHist;
    public MacdStrategy(String id, int fast, int slow, int signal, double qty) {
        this.id = id; this.kf = 2.0/(fast+1); this.ks = 2.0/(slow+1); this.ksig = 2.0/(signal+1); this.qty = qty;
    }
    @Override public String id() { return id; }
    @Override public void onBar(Kline b, StrategyContext ctx) {
        if (!b.closed()) return;
        double c = b.close();
        emaF = emaF == null ? c : c*kf + emaF*(1-kf);
        emaS = emaS == null ? c : c*ks + emaS*(1-ks);
        double dif = emaF - emaS;
        dea = dea == null ? dif : dif*ksig + dea*(1-ksig);
        double hist = dif - dea;
        if (prevHist != null) {
            if (prevHist <= 0 && hist > 0 && ctx.position(b.symbol()) <= 0) ctx.submit(o(b.symbol(), OrderSide.BUY));
            else if (prevHist >= 0 && hist < 0 && ctx.position(b.symbol()) > 0) ctx.submit(o(b.symbol(), OrderSide.SELL));
        }
        prevHist = hist;
    }
    private OrderIntent o(String s, OrderSide side){ return new OrderIntent(UUID.randomUUID().toString(), id, s, side, OrderType.MARKET, null, qty, false, "SPOT", "main"); }
}
