package com.quant.common.strategy;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** 動量機器人：N 期報酬 > 門檻買、< -門檻賣。 */
public class MomentumStrategy implements Strategy {
    private final String id; private final int period; private final double threshold, qty;
    private final Deque<Double> closes = new ArrayDeque<>();
    public MomentumStrategy(String id,int period,double threshold,double qty){ this.id=id; this.period=period; this.threshold=threshold; this.qty=qty; }
    @Override public String id(){ return id; }
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed()) return;
        closes.addLast(b.close());
        while(closes.size()>period+1) closes.removeFirst();
        if(closes.size()<period+1) return;
        double mom=b.close()/closes.peekFirst()-1;
        if(mom>threshold && ctx.position(b.symbol())<=0) ctx.submit(o(b.symbol(),OrderSide.BUY));
        else if(mom<-threshold && ctx.position(b.symbol())>0) ctx.submit(o(b.symbol(),OrderSide.SELL));
    }
    private OrderIntent o(String s,OrderSide side){ return new OrderIntent(UUID.randomUUID().toString(),id,s,side,OrderType.MARKET,null,qty,false,"SPOT","main"); }
}
