package com.quant.common.strategy;
import com.quant.common.dto.Kline;
import java.util.*;
/** 加權均線 WMA 交叉(快/慢)。 */
public class WmaStrategy extends BaseStrategy {
    private final int fast,slow; private final Deque<Double> c=new ArrayDeque<>(); private Double prevDiff;
    public WmaStrategy(String id,int fast,int slow,double qty){super(id,qty);this.fast=fast;this.slow=slow;}
    private double wma(int n){ List<Double> l=new ArrayList<>(c); int sz=l.size(); double num=0,den=0; for(int i=0;i<n;i++){double w=n-i; num+=l.get(sz-1-i)*w; den+=w;} return num/den; }
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return; c.addLast(b.close()); while(c.size()>slow)c.removeFirst(); if(c.size()<slow)return;
        double diff=wma(fast)-wma(slow);
        if(prevDiff!=null){ if(prevDiff<=0&&diff>0)buy(ctx,b.symbol()); else if(prevDiff>=0&&diff<0)sell(ctx,b.symbol()); }
        prevDiff=diff;
    }
}
