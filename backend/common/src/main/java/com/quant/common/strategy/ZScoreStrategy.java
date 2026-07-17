package com.quant.common.strategy;
import com.quant.common.dto.Kline;
import java.util.*;
/** Z 分數均值回歸：z<-th 買、z>th 賣。 */
public class ZScoreStrategy extends BaseStrategy {
    private final int p; private final double th; private final Deque<Double> c=new ArrayDeque<>();
    public ZScoreStrategy(String id,int period,double threshold,double qty){super(id,qty);this.p=period;this.th=threshold;}
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return; c.addLast(b.close()); while(c.size()>p)c.removeFirst(); if(c.size()<p)return;
        double m=c.stream().mapToDouble(x->x).average().orElse(0);
        double v=c.stream().mapToDouble(x->(x-m)*(x-m)).average().orElse(0), sd=Math.sqrt(v);
        double z=sd==0?0:(b.close()-m)/sd;
        if(z<-th)buy(ctx,b.symbol()); else if(z>th)sell(ctx,b.symbol());
    }
}
