package com.quant.common.strategy;
import com.quant.common.dto.Kline;
import java.util.*;
/** 布林突破(順勢)：突破上軌買、跌破下軌賣。 */
public class BollBreakStrategy extends BaseStrategy {
    private final int p; private final double mult; private final Deque<Double> c=new ArrayDeque<>();
    public BollBreakStrategy(String id,int period,double mult,double qty){super(id,qty);this.p=period;this.mult=mult;}
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return; c.addLast(b.close()); while(c.size()>p)c.removeFirst(); if(c.size()<p)return;
        double m=c.stream().mapToDouble(x->x).average().orElse(0);
        double v=c.stream().mapToDouble(x->(x-m)*(x-m)).average().orElse(0), sd=Math.sqrt(v);
        if(b.close()>m+mult*sd)buy(ctx,b.symbol()); else if(b.close()<m-mult*sd)sell(ctx,b.symbol());
    }
}
