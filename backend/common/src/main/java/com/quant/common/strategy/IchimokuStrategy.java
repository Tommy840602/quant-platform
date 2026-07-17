package com.quant.common.strategy;
import com.quant.common.dto.Kline;
import java.util.*;
/** 一目均衡：轉換線上穿基準線買、下穿賣。 */
public class IchimokuStrategy extends BaseStrategy {
    private final Deque<double[]> hl = new ArrayDeque<>(); private Double prevDiff;
    public IchimokuStrategy(String id,double qty){super(id,qty);}
    private double mid(int n){double hh=-1e18,ll=1e18;int i=0;for(var it=hl.descendingIterator();it.hasNext()&&i<n;i++){double[] x=it.next();hh=Math.max(hh,x[0]);ll=Math.min(ll,x[1]);}return (hh+ll)/2;}
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return; hl.addLast(new double[]{b.high(),b.low()}); while(hl.size()>52)hl.removeFirst();
        if(hl.size()<26)return; double diff=mid(9)-mid(26);
        if(prevDiff!=null){ if(prevDiff<=0&&diff>0)buy(ctx,b.symbol()); else if(prevDiff>=0&&diff<0)sell(ctx,b.symbol()); }
        prevDiff=diff;
    }
}
