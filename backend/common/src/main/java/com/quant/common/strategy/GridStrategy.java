package com.quant.common.strategy;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;

import java.util.UUID;

/** 網格機器人：價格每跌 gridPct 買一格、每漲 gridPct 賣一格（僅示意）。 */
public class GridStrategy implements Strategy {

    private final String id;
    private final double gridPct, qty;
    private Double ref;

    public GridStrategy(String id, double gridPct, double qty) {
        this.id = id; this.gridPct = gridPct; this.qty = qty;
    }

    @Override public String id() { return id; }

    @Override
    public void onBar(Kline bar, StrategyContext ctx) {
        if (!bar.closed()) return;
        double px = bar.close();
        if (ref == null) { ref = px; return; }
        if (px <= ref * (1 - gridPct)) { ctx.submit(order(bar.symbol(), OrderSide.BUY)); ref = px; }
        else if (px >= ref * (1 + gridPct)) { ctx.submit(order(bar.symbol(), OrderSide.SELL)); ref = px; }
    }

    private OrderIntent order(String symbol, OrderSide side) {
        return new OrderIntent(UUID.randomUUID().toString(), id, symbol, side, OrderType.MARKET, null, qty, false, "SPOT", "main");
    }
}
