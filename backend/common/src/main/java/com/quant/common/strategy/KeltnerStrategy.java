package com.quant.common.strategy;
import com.quant.common.dto.Kline;
import java.util.*;
/** 肯特納通道：突破 EMA+mult*ATR 買、跌破 EMA-mult*ATR 賣。 */
public class KeltnerStrategy extends BaseStrategy {
    private final int p; private final double mult, k; private Double ema, prevClose; private final Deque<Double> tr=new ArrayDeque<>();
    public KeltnerStrategy(String id,int period,double mult,double qty){super(id,qty);this.p=period;this.mult=mult;this.k=2.0/(period+1);}
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return; double c=b.close(); ema=ema==null?c:c*k+ema*(1-k);
        double t=prevClose==null?b.high()-b.low():Math.max(b.high()-b.low(),Math.max(Math.abs(b.high()-prevClose),Math.abs(b.low()-prevClose)));
        prevClose=c; tr.addLast(t); while(tr.size()>p)tr.removeFirst(); if(tr.size()<p)return;
        double atr=tr.stream().mapToDouble(x->x).average().orElse(0);
        if(c>ema+mult*atr)buy(ctx,b.symbol()); else if(c<ema-mult*atr)sell(ctx,b.symbol());
    }
}
