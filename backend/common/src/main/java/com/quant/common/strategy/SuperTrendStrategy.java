package com.quant.common.strategy;
import com.quant.common.dto.Kline;
import java.util.*;
/** SuperTrend：ATR 通道追蹤，翻多買、翻空賣。 */
public class SuperTrendStrategy extends BaseStrategy {
    private final int period; private final double mult;
    private final Deque<Double> tr = new ArrayDeque<>();
    private Double prevClose; private int trend = 1; private double st = 0;
    public SuperTrendStrategy(String id,int period,double mult,double qty){super(id,qty);this.period=period;this.mult=mult;}
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return;
        double t = prevClose==null? b.high()-b.low() : Math.max(b.high()-b.low(),Math.max(Math.abs(b.high()-prevClose),Math.abs(b.low()-prevClose)));
        prevClose=b.close(); tr.addLast(t); while(tr.size()>period)tr.removeFirst();
        if(tr.size()<period)return;
        double atr=tr.stream().mapToDouble(x->x).average().orElse(0), mid=(b.high()+b.low())/2;
        double up=mid+mult*atr, dn=mid-mult*atr;
        int prev=trend;
        if(b.close()>st) trend=1; else if(b.close()<st) trend=-1;
        st = trend==1? dn : up;
        if(prev<0 && trend>0) buy(ctx,b.symbol()); else if(prev>0 && trend<0) sell(ctx,b.symbol());
    }
}
