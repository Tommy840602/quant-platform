package com.quant.common.strategy;
import com.quant.common.dto.Kline;
import java.util.*;
/** ROC 變化率：ROC>門檻買、<-門檻賣。 */
public class RocStrategy extends BaseStrategy {
    private final int p; private final double th; private final Deque<Double> c=new ArrayDeque<>();
    public RocStrategy(String id,int period,double threshold,double qty){super(id,qty);this.p=period;this.th=threshold;}
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return; c.addLast(b.close()); while(c.size()>p+1)c.removeFirst(); if(c.size()<p+1)return;
        double roc=b.close()/c.peekFirst()-1;
        if(roc>th)buy(ctx,b.symbol()); else if(roc<-th)sell(ctx,b.symbol());
    }
}
