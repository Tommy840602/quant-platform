package com.quant.common.strategy;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** CCI 機器人：CCI<-100 超賣買、>100 超買賣。 */
public class CciStrategy implements Strategy {
    private final String id; private final int period; private final double qty;
    private final Deque<Double> tps = new ArrayDeque<>();
    public CciStrategy(String id, int period, double qty){ this.id=id; this.period=period; this.qty=qty; }
    @Override public String id(){ return id; }
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed()) return;
        double tp=(b.high()+b.low()+b.close())/3; tps.addLast(tp);
        while(tps.size()>period) tps.removeFirst();
        if(tps.size()<period) return;
        double mean=tps.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double md=tps.stream().mapToDouble(x->Math.abs(x-mean)).average().orElse(0);
        double cci= md==0?0:(tp-mean)/(0.015*md);
        if(cci<-100 && ctx.position(b.symbol())<=0) ctx.submit(o(b.symbol(),OrderSide.BUY));
        else if(cci>100 && ctx.position(b.symbol())>0) ctx.submit(o(b.symbol(),OrderSide.SELL));
    }
    private OrderIntent o(String s,OrderSide side){ return new OrderIntent(UUID.randomUUID().toString(),id,s,side,OrderType.MARKET,null,qty,false,"SPOT","main"); }
}
