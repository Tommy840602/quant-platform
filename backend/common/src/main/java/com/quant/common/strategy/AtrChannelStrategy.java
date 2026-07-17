package com.quant.common.strategy;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** ATR 通道突破機器人：收盤 > 均價+mult*ATR 買、< 均價-mult*ATR 賣。 */
public class AtrChannelStrategy implements Strategy {
    private final String id; private final int period; private final double mult, qty;
    private final Deque<Double> closes = new ArrayDeque<>();
    private final Deque<Double> trs = new ArrayDeque<>();
    private Double prevClose;
    public AtrChannelStrategy(String id,int period,double mult,double qty){ this.id=id; this.period=period; this.mult=mult; this.qty=qty; }
    @Override public String id(){ return id; }
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed()) return;
        double tr = prevClose==null ? b.high()-b.low()
                : Math.max(b.high()-b.low(), Math.max(Math.abs(b.high()-prevClose), Math.abs(b.low()-prevClose)));
        prevClose=b.close();
        trs.addLast(tr); while(trs.size()>period) trs.removeFirst();
        closes.addLast(b.close()); while(closes.size()>period) closes.removeFirst();
        if(closes.size()<period) return;
        double mid=closes.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double atr=trs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if(b.close()>mid+mult*atr && ctx.position(b.symbol())<=0) ctx.submit(o(b.symbol(),OrderSide.BUY));
        else if(b.close()<mid-mult*atr && ctx.position(b.symbol())>0) ctx.submit(o(b.symbol(),OrderSide.SELL));
    }
    private OrderIntent o(String s,OrderSide side){ return new OrderIntent(UUID.randomUUID().toString(),id,s,side,OrderType.MARKET,null,qty,false,"SPOT","main"); }
}
