package com.quant.common.strategy;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** 威廉 %R 機器人：%R<-80 超賣買、>-20 超買賣。 */
public class WilliamsRStrategy implements Strategy {
    private final String id; private final int period; private final double qty;
    private final Deque<double[]> hl = new ArrayDeque<>();
    public WilliamsRStrategy(String id,int period,double qty){ this.id=id; this.period=period; this.qty=qty; }
    @Override public String id(){ return id; }
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed()) return;
        hl.addLast(new double[]{b.high(),b.low()});
        while(hl.size()>period) hl.removeFirst();
        if(hl.size()<period) return;
        double hh=-1e18,ll=1e18;
        for(double[] x:hl){ hh=Math.max(hh,x[0]); ll=Math.min(ll,x[1]); }
        double r= hh==ll?-50:(hh-b.close())/(hh-ll)*-100;
        if(r<-80 && ctx.position(b.symbol())<=0) ctx.submit(o(b.symbol(),OrderSide.BUY));
        else if(r>-20 && ctx.position(b.symbol())>0) ctx.submit(o(b.symbol(),OrderSide.SELL));
    }
    private OrderIntent o(String s,OrderSide side){ return new OrderIntent(UUID.randomUUID().toString(),id,s,side,OrderType.MARKET,null,qty,false,"SPOT","main"); }
}
