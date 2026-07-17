package com.quant.common.strategy;
import com.quant.common.dto.Kline;
import java.util.*;
/** Dual Thrust：突破 open+k*range 買、跌破 open-k*range 賣。 */
public class DualThrustStrategy extends BaseStrategy {
    private final int p; private final double k; private final Deque<double[]> hlc=new ArrayDeque<>();
    public DualThrustStrategy(String id,int period,double k,double qty){super(id,qty);this.p=period;this.k=k;}
    @Override public void onBar(Kline b, StrategyContext ctx){
        if(!b.closed())return;
        if(hlc.size()>=p){ double hh=-1e18,ll=1e18,hc=-1e18,lc=1e18;
            for(double[] x:hlc){hh=Math.max(hh,x[0]);ll=Math.min(ll,x[1]);hc=Math.max(hc,x[2]);lc=Math.min(lc,x[2]);}
            double range=Math.max(hh-lc,hc-ll);
            if(b.close()>b.open()+k*range)buy(ctx,b.symbol()); else if(b.close()<b.open()-k*range)sell(ctx,b.symbol()); }
        hlc.addLast(new double[]{b.high(),b.low(),b.close()}); while(hlc.size()>p)hlc.removeFirst();
    }
}
