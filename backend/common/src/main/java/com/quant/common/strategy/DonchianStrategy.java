package com.quant.common.strategy;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** 唐奇安通道突破機器人：突破 N 期最高買、跌破 N 期最低賣。 */
public class DonchianStrategy implements Strategy {
    private final String id; private final int period; private final double qty;
    private final Deque<double[]> hl = new ArrayDeque<>();
    public DonchianStrategy(String id,int period,double qty){ this.id=id; this.period=period; this.qty=qty; }
    @Override public String id(){ return id; }
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed()) return;
        if(hl.size()>=period){
            double hh=-1e18,ll=1e18;
            for(double[] x:hl){ hh=Math.max(hh,x[0]); ll=Math.min(ll,x[1]); }
            if(b.close()>hh && ctx.position(b.symbol())<=0) ctx.submit(o(b.symbol(),OrderSide.BUY));
            else if(b.close()<ll && ctx.position(b.symbol())>0) ctx.submit(o(b.symbol(),OrderSide.SELL));
        }
        hl.addLast(new double[]{b.high(),b.low()});
        while(hl.size()>period) hl.removeFirst();
    }
    private OrderIntent o(String s,OrderSide side){ return new OrderIntent(UUID.randomUUID().toString(),id,s,side,OrderType.MARKET,null,qty,false,"SPOT","main"); }
}
